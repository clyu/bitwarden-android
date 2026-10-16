# Decentralized Bitwarden: the Android app

This fork of the Bitwarden Android app works without a Bitwarden server. Everything a server
would have stored is kept in files on the device, and devices share a vault by sharing those
files. The change was made in the commit "replace the Bitwarden server with an on-device vault".
It is named by its title here, as its hash does not stay the same when the history of the fork is
rewritten: `git log --grep="replace the Bitwarden server"` finds it.

The server is replaced by the **local vault**, which every client of this fork shares: it is the
git submodule `local-vault-rs`, in Rust. What it does, and what a client has to bring to it, is
described there, along with the format of the vault folder, which is the contract between
clients:

- [local-vault-rs/docs/DESIGN.md](../local-vault-rs/docs/DESIGN.md): the design that every client
  shares, how each job of the server is done, sharing a vault, and the limits to keep in mind.
- [local-vault-rs/docs/LOCAL_VAULT_FORMAT.md](../local-vault-rs/docs/LOCAL_VAULT_FORMAT.md): the
  files of the vault folder.

This document covers what the app brings to the local vault, and what to do when updating from
upstream.

- [How the app hands its requests over](#how-the-app-hands-its-requests-over)
- [The vault folder](#the-vault-folder)
- [Logging in on the device](#logging-in-on-the-device)
- [Making sure nothing reaches a real server](#making-sure-nothing-reaches-a-real-server)
- [What is changed in upstream files](#what-is-changed-in-upstream-files)
- [Updating from upstream](#updating-from-upstream)
- [Building](#building)
- [Code map](#code-map)

## How the app hands its requests over

```
UI, ViewModels, repositories, SDK       unchanged
API services (Retrofit)                 unchanged
HTTP client (OkHttp)
  +- LocalServerInterceptor             hands every request over as it is
       +- LocalServer                   streams the content of attachments
            +- local vault (Rust)       works out what a request asks for, and answers it
                 +- LocalVaultFileSystem
                      +- storage of the app, or
                      +- a folder the user chose
```

`LocalServerInterceptor` is installed on every HTTP client that talks to a Bitwarden service (API,
identity, events), where it answers all requests, whatever their address. On the client for
arbitrary URLs, which uploads and downloads attachments, it answers only requests for the
reserved host `vault.local.invalid` and lets the others go out.

It runs after the bearer token is added to a request and before the address of the server is
filled in, and hands the request to the local vault as it is: its method, its address, its
headers and its body. The local vault works out the rest, as it does for every client. The
interceptor only adds the `Content-Type` of the body to the headers, which OkHttp does after the
interceptors of the app.

`LocalServer` gives the local vault the vault folder, the clock and the ID of this installation
of the app, through UniFFI, and turns a folder that cannot be reached into an `IOException`,
which the app already handles as a failed network call. The local vault fails the same way for
an account of which files have come to the device, but none with the record of the account or
none with that of its master password yet, and for an email that no other account has while that
is so, as answering that the account is not there would log it out, and answering that no account
has the email would have the app take the master password to be wrong or register the email
again (`LOCAL_VAULT_FORMAT.md`, "Putting the vault together"). It handles one request at a time,
and streams the content of attachments: it leaves the body of a request for an attachment out of
what it hands over, writes it to where the response says, and opens the file that a download is.
A call of the local vault that fails in any other way is answered with a 500, as nothing may
escape from an interceptor: OkHttp rethrows it on a thread of its own, which would take the app
down.

The app shows such a failure as a network error, which does not say which account it is about,
so the local vault logs the ID of the account as a warning (`Local server cannot use account
<account id>`), which is the name of its directory in the vault folder. Timber hands it to the
flight recorder of the app (**Settings › About › Flight recorder**), and to logcat in a build with
logs. An account whose files will never all come keeps failing so, and with it every login,
prelogin and registration for an email that no other account has, until its directory is
deleted by hand: one that a client from before the `vault.json` that says that an account was
deleted left behind (see "Building"), or one of which the directory of a device was deleted by
hand. Its files tell such a directory as well: its `vault.json` is missing or says that it has
version 2, and none of the files in its `devices` has `account` in its `records`, or none has
`masterPassword` there.

The ID of the installation is `AppIdProvider.uniqueAppId`, the UUID that the app identifies itself
to a Bitwarden server with. The local vault writes the changes of the app to a directory of the
vault folder named after it, `<account id>/devices/<that ID>/`, which no other device writes, and
puts the vault together from the directories of every device, so that a tool that syncs the
folder never has two versions of a file to choose from. The app makes the ID the first time that
it needs it, under a lock, so that two threads that ask for it at once do not each make one, and
keeps it in its own storage, which the backup rules of the app leave out (`backup_rules.xml`,
`data_extraction_rules.xml`): a phone that is set up from a backup of another one gets an ID of
its own. Clearing the data of the app, or uninstalling it and installing it
again, gives it a new ID, and updating it does not. Where the vault is in a folder that the user
chose, the directory of the one before then stays in it, and must not be deleted by hand, as part
of the vault may be only in it; the storage of the app, which has the vault otherwise, is emptied
along with the ID. As nothing writes that directory anymore, it can also keep the user key
encrypted with the master password of that time for good, so changing the master password is not
enough if someone else may know the old one (DESIGN.md, "Limits to keep in mind").

## The vault folder

`LocalVaultFileSystem` has the six operations that the local vault keeps a vault on. There are
two implementations. `FileLocalVaultFileSystem` works on ordinary files and makes sure that a file
is on storage by the time it is closed. `SafLocalVaultFileSystem` works on a folder that the user
chose with the folder picker of Android, which is not reachable as ordinary files, and which
cannot rename a file over an existing one. `FileLocalVaultFileSystemTest` checks the first one
against the rules of the local vault (`checkFileSystem`).

Until the user chooses a folder, the vault is in the storage of the app
(`Android/data/<application id>/files/vault/`). Under **Settings › Other › Vault folder** the
user can choose a folder that is backed up or synced. `LocalVaultStorageManager` then copies the
accounts that the folder does not have yet, and never overwrites an account that is already
there, which is what makes it safe to choose a folder that another device has filled. Of an
account that the folder has, it copies what the app wrote that the folder does not have yet: the
files of the directory of the app in the account, which the folder has otherwise or not at all,
and the content of attachments that the folder does not have. No other device writes that
directory, so the files that the app has are the ones that it wrote last, as when the folder was
filled with a copy of the storage of the app from before its last changes. The files of other
devices are never copied. What is copied is the rule of the local vault (`accountsMissingIn`,
`deviceFilesMissingIn`), which `LocalVaultCopyTest` checks the copy against: the copy does not
call the local vault, so that the unit tests of the app do not need its library.

The folder can only be changed while no account is logged in, as the cached data of a logged-in
account would belong to a vault that is not in the new folder. The file system that the network
layer holds is a thin object that looks up the current folder on every call, so the HTTP client
does not have to be rebuilt when the folder changes.

## Logging in on the device

Where upstream says that the user logs in on a server, or creates an account on one, the app says
that this happens on this device: under the email address on the landing screen and on the screen
for creating an account, on the screen of the master password, and on the lock screen. The first
two have no selector of the environment, as they offered the environment as where to log in or to
create the account. The account switcher, and the dialogs it opens, name no environment under the
email address of an account.

The environment is still there, and still decides where the icons of websites come from (see
below) and which website the links of the app open, but it can no longer be chosen: it stays what
it was, which is Bitwarden in the United States unless another one was chosen in an earlier
version of the app. `AccountSummary.environmentLabel` is still filled in, as the landing screen
tells by it whether an account is already logged in.

## Making sure nothing reaches a real server

The measures of the local vault apply (see "Making sure nothing reaches a real server" in
DESIGN.md). In the app:

1. **The addresses of the server are ones that cannot exist.** `LocalBaseUrlsProvider` reports
   `https://vault.local.invalid` as the API, identity and events server.
2. **The SDK is given the same addresses.** The SDK of this app makes its requests with an HTTP
   client of its own, which `LocalServerInterceptor` does not see, so they fail instead of being
   answered. The browser extension answers them, as its SDK uses `fetch`.
3. **No feature flags are reported.** `LocalVaultFeatureFlagsTest` checks that every flag of the
   app is off by default, and that the flags that the local vault lists for the app
   (`flagsThatMustBeOff`) are still there. Some of them hand a step to the SDK, such as prelogin
   (`pm-27060-password-prelogin-from-sdk`), which would then fail.

Separately, the app is told that the network is always connected. The app checks the network
before some operations and refuses them when offline, which makes no sense for a vault that is
on the device.

What still leaves the device is what never went to the vault API in the first place: checking a
password against Have I Been Pwned, verifying Digital Asset Links for passkeys, and loading the
website icons of items from the icon service of the environment that is selected in the app,
which is the one of Bitwarden by default.

## What is changed in upstream files

Everything else is new: the files in the directories of the code map below, the submodule
`local-vault-rs` with `.gitmodules`, and the strings of the app (`strings_local_vault.xml`).

| File | Change |
|------|--------|
| `network/build.gradle.kts` | Builds the local vault from the submodule, adds it to every variant, and depends on JNA. |
| `gradle/libs.versions.toml` | JNA. |
| `network/.../BitwardenServiceClientImpl.kt`, `network/.../model/BitwardenServiceClientConfig.kt` | `localVaultFileSystem`: setting it is what turns the local server on. The local server is given `appIdProvider.uniqueAppId` as the ID of the device. |
| `network/.../retrofit/RetrofitsImpl.kt` | Installs `LocalServerInterceptor` on each HTTP client. |
| `app/.../datasource/network/di/PlatformNetworkModule.kt` | Hands the file system and `LocalBaseUrlsProvider` to the network layer. |
| `app/.../auth/datasource/disk/AuthDiskSourceImpl.kt` | `uniqueAppId` is made under a lock, as it names the directory that the local vault writes the changes of the app to. |
| `app/.../manager/network/NetworkConnectionManagerImpl.kt` | The network that is always connected. |
| `app/.../ui/platform/feature/settings/other/` | The setting for the vault folder. |
| `app/.../ui/auth/feature/landing/LandingScreen.kt`, `.../startregistration/StartRegistrationScreen.kt`, `.../login/LoginScreen.kt`, `.../vaultunlock/VaultUnlockScreen.kt`, and their tests | Logging in on the device: no server is named, and the landing screen and the screen for creating an account have no selector of the environment. |
| `ui/.../components/account/BitwardenAccountSwitcher.kt`, `.../account/dialog/BitwardenLogoutConfirmationDialog.kt`, `.../account/dialog/BitwardenRemovalConfirmationDialog.kt`, `ui/src/testFixtures/.../BitwardenAccountSwitcherTestHelpers.kt` | The account switcher and its dialogs name no environment. |
| `.github/workflows/android.yml` | Builds the local vault with the app, and runs the tests of the local vault. |

## Updating from upstream

1. Merge, and resolve conflicts in the files above.
2. Run the tests of the network module: `./gradlew :network:testDebugUnitTest`.
   - `LocalServerEndpointInventoryTest` fails when a request was added or removed. Its lists are
     the requests that the app can make, and it checks them against the route table of the local
     vault (`isAnswered`). Decide for each changed line whether the local server has to answer
     it, and add a route to `local-vault-rs` if so. A request that disappeared from the app may
     have moved into the SDK, which this app does not hand to the local server.
   - `LocalVaultFeatureFlagsTest` fails when a flag is on by default, or a flag that the local
     vault counts on was removed: what it turned on now happens for everyone.
   - `LocalServiceClientTest` runs the API services of the app against the local server, and
     fails when what the app sends, or what it expects back, changed in a way that the local
     server does not follow.
3. Look for `Local server refused:` in the log of a debug build, which shows a request that the
   local server does not answer.

## Building

The local vault is built along with the app, from the submodule, so building the app takes Rust
on top of what upstream needs:

1. Check the submodule out: `git submodule update --init`.
2. Install Rust with [rustup](https://rustup.rs). The submodule pins the version in its
   `rust-toolchain.toml`, which rustup installs the first time that it builds.
3. Add the targets of Android, in `local-vault-rs`:

   ```sh
   rustup target add aarch64-linux-android armv7-linux-androideabi \
       x86_64-linux-android i686-linux-android
   ```

4. Install [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) (`cargo install cargo-ndk`), which
   finds the Android NDK by `ANDROID_NDK_HOME` or in the Android SDK.

`network/build.gradle.kts` builds the library for each ABI that the app is built for, which
`-PabiFilters=arm64-v8a` limits to one, and the library for the machine that builds, which the
Kotlin of the local vault is generated from and which the unit tests run. The unit tests need no
NDK and no targets of Android.

To change the local vault, change it in its own repository, where it is tested, and update the
submodule here to that commit. The browser extension has the same submodule: a change to the
format of the vault folder has to reach both before either of them writes it. Version 2, in which
every device writes a directory of its own, is such a change. The first write of an updated
client takes a vault of version 1 over, and from then on a client that knows only version 1 does
not write the account, but does not have it either: its requests for the account are refused as
unauthorized, which logs the account out; the email of the account is taken to belong to no
account, so the master key is derived with the default KDF settings and the master password is
taken to be wrong; and the email can be registered again, after which an updated client can log
in to the new, empty account (`LOCAL_VAULT_FORMAT.md`, "Accounts with the same email address").
So the app is released with such a change before the browser extension, or at the same time, and
not after it: a browser can update the extension before the user has updated the app.

The `vault.json` that says that an account was deleted (`LOCAL_VAULT_FORMAT.md`, "Deleting an
account") is a change of the same kind, the other way round. A client from before it takes such
an account for one of a layout that it does not know, which it leaves alone. But it deletes the
whole directory of an account that it deletes, which can leave behind the file of a device that
changed the vault while the deletion was on its way to it. An updated client takes that file
for an account whose other files are still on their way, for good: its requests, and those for
an email that no other account has, fail until the directory is deleted by hand. If the file that
is left holds the records of the account and of its master password, as that of the device that
took the vault of version 1 over does, the account is back instead, without the content of its
attachments.

## Code map

| Path | Content |
|------|---------|
| `local-vault-rs/` | The local vault in Rust, a submodule: the route table, the handlers, the vault store, the rules that the app checks itself against, and its tests. |
| `network/build.gradle.kts` | Builds the local vault and generates its Kotlin. |
| `network/src/main/kotlin/com/bitwarden/network/local/` | `LocalServer`, which hands requests to the local vault, the interceptor, the file-system interface and its implementation on ordinary files, and the copy of the accounts, and of the files of the app in the accounts that the other folder has, to another folder. |
| `network/.../retrofit/RetrofitsImpl.kt` | Where the interceptor is installed on each HTTP client. |
| `app/src/main/kotlin/com/x8bit/bitwarden/data/platform/manager/localvault/` | The vault folder: the manager, the implementation of the file system on a folder the user chose, and the addresses that cannot be reached. |
| `app/.../datasource/network/di/PlatformNetworkModule.kt` | Where the app hands the file system and the addresses to the network layer. |
| `app/.../manager/network/NetworkConnectionManagerImpl.kt` | The network that is always connected. |
| `app/.../ui/platform/feature/settings/other/` | The setting for the vault folder. |
| `network/src/test/kotlin/com/bitwarden/network/local/` | Tests of the local server as the app uses it: through the interceptor, with the API services of the app, for every request that the app can make, for its feature flags, and for its file system. How the local server behaves is tested in `local-vault-rs`. |
