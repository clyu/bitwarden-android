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
- [Changing how the master key is derived](#changing-how-the-master-key-is-derived)
- [Changing the master password](#changing-the-master-password)
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
`masterPassword` there. An account whose `vault.json` still holds a whole vault of version 1
fails the same way, and the warning says that its vault has the layout of version 1: no device
took that vault over before the local vault stopped reading that layout (see "Building").

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
of the vault may be only in it: retire it instead (see "Retiring a device"); the storage of the
app, which has the vault otherwise, is emptied along with the ID. As nothing writes that
directory anymore, it also keeps the user key encrypted with the master password of that time
until 90 days after it is retired, so changing the master password is not enough if someone else
may know the old one (DESIGN.md, "Limits to keep in mind").

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
devices are never copied. What is copied is worked out by the local vault (`accountsMissingIn`,
`deviceFilesMissingIn`), and `LocalVaultCopier` only copies it, as a stream. The manager is
given the copier, so that the unit tests of the app do not need the library of the local vault.
`LocalVaultCopierTest`, in the network module, tests the copy with the local vault.

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

## Changing how the master key is derived

**Settings › Account security › Encryption key settings** changes how the master key is derived
from the master password: with PBKDF2-SHA256 or with Argon2id, and with how much work. Upstream has
that page only in the web vault, which does not exist here; the app itself only raises
PBKDF2-SHA256 iterations that are below the minimum, when a feature flag of the Bitwarden server
turns that on. The screen offers the same settings as the one of the browser extension, and takes
only the values that it takes (`KdfSettingsConstants`). The local vault takes all of them: it
refuses settings outside of the ranges of the Bitwarden server (DESIGN.md, "How each job of the
server is done"). Its texts are in `strings_local_vault.xml`, apart from those that upstream has.

Once the master password is confirmed, the SDK encrypts the user key again with the master key that
the new settings derive (`makeUpdateKdf`), and `KdfSettingsManager` sends it to the local vault
with the API service of upstream (`POST accounts/kdf`), which keeps it in place of the one before,
along with what the master password is recognized by now. The SDK does not send it itself, as the
requests that it makes are never answered (see below). For the same reason, the app does not change
the settings with the client of the SDK that is meant for that, `user_crypto_management()`, whose
`change_kdf` sends the request itself. The user key stays the same, so the items do not change, and
no device is logged out.

The SDK keeps the settings that the vault was unlocked with until it is locked. They are the ones
from before once the settings changed, on this device or on another one whose change a sync took,
so the app proves the master password with the hash of the settings that the account has, and not
with the one that the SDK derives. The screen syncs before it shows the settings, as the one of the
browser extension does, as a change made with the settings from before would be refused as if the
master password were wrong. If the local vault refuses it all the same, as another device changed
the settings in the meantime, the screen syncs again and asks the user to try again.

The other devices that share the vault folder take the new settings at their next sync. Until then,
a device that has not synced still unlocks with the settings from before, but when it proves the
master password to the local vault, such as to delete the account, the master password is taken to
be wrong.

Stronger settings do not protect a copy of the vault folder that was made before, nor what the
folder keeps from before: the user key encrypted with the master key of the settings from before
stays in it in the same places as after a change of the master password (DESIGN.md, "Limits to
keep in mind"), and the master password is as easy to guess from it as it was.

The app checks the master password, such as before it shows an item that asks for it again, against
a hash that it keeps from the last time that the vault was unlocked with it, by comparing it with
the hash that the SDK derives with the settings that the vault was unlocked with. Once the settings
changed, on this device or on another one, and the vault was unlocked with the new ones in another
way than with the master password, such as with biometrics, the two no longer match.
`AuthRepositoryImpl.validatePassword` then checks the master password against the user key instead,
as it does when it keeps no hash, and keeps the hash of the new settings.

## Changing the master password

**Settings › Account security › Change master password** changes the master password in the app,
where upstream opens the web vault for it, which does not exist here. The screen asks for the
current master password, for the new one twice and for a hint. It takes a new master password of at
least 12 characters that is not the current one, and a hint that is not the new master password, as
whoever can read the vault folder can read the hint. Like the screen that creates an account, it
asks the user to confirm a new master password that is weak, and, unless the user turns that check
off, one that Have I Been Pwned knows from a data breach (see "Making sure nothing reaches a real
server"). A check of Have I Been Pwned that fails does not keep the master password from being
changed. The hint replaces the one before, which the app does not keep, so the field starts empty,
and a master password that is changed without a hint has none. Its texts are in
`strings_local_vault.xml`, apart from those that upstream has.

The app checks the current master password itself first, so that one that is mistyped is told as
such before the new one is checked against data breaches, or anything is sent to the local vault.
`MasterPasswordManager` then has the SDK encrypt the user key with the master key of the new
master password, and sends it to the local vault with the API service of upstream
(`POST accounts/password`), which keeps it in place of the one before, along with what the new
master password is recognized by, its hint and a new security stamp. The user key stays the same,
so the items do not change. The new security stamp logs the account out of the other devices that
share the vault folder at their next sync, as they unlock it with the old master password. The app
logs it out of this device right away, as upstream does once the master password is reset, and the
account is logged in to again with the new master password.

The current master password is proven with the hash of the KDF settings that the account has, as
for the encryption key settings, and the new master key is derived with them as well, as the local
vault takes a new master password with no other settings. The password reset of upstream
(`AuthRepository.resetPassword`) cannot be used for it: the SDK derives the new master key there
(`makeUpdatePassword`) with the settings that the vault was unlocked with, which are the ones from
before once the settings changed, on this device or on another one whose change a sync took.
Upstream does not have to expect that: its app has no screen that changes the settings, and a
change of them logs every other device out there. The vault would then keep the user key encrypted
with a master key that the settings of the account do not derive, and the account could no longer
be unlocked or logged in to. `MasterPasswordManager` has the SDK derive it with `makeUpdateKdf`
instead, given the new master password and the settings of the account. The SDK says in the
documentation of that function that it is deprecated, and points to its client
`user_crypto_management()` instead, which the app cannot use here: that client sends its requests
itself (see "Changing how the master key is derived"), and changes the master password only along
with the user key (`password_change_and_rotate_user_keys`). The browser extension changes the
settings with its `change_kdf`, as the requests of its SDK reach the local vault, and the master
password with functions that only the SDK for JavaScript has (`PureCrypto`). Once `makeUpdateKdf`
is removed, this and the encryption key settings therefore need another way to derive a master key
with settings that they give.

Like the screen of the encryption key settings, the screen syncs before it shows the form. If the
local vault refuses the current master password all the same, it syncs again: a change of the
master password on another device logs the account out of this one, and a change of the settings
has the user try again.

A new master password does not lock out whoever knows the old one: the vault folder, and any copy
of it, can still hold the user key encrypted with the master key of the old one (DESIGN.md, "Limits
to keep in mind"). The screen says so, and what to do if someone else may know the master password:
change it in the browser extension, and rotate the user key there along with it (below).

## A rotation of the user key on another device

The app does not rotate the user key. Upstream does that in the web vault only, and the client of
the SDK that does it (`user_crypto_management()`) sends its requests itself, which do not reach
the local vault here (see "Changing how the master key is derived"). The browser extension does it,
along with a change of the master password (`docs/DESIGN.md` of `local-vault-rs`, "Rotating the
user key", and `apps/browser/src/local-vault/README.md` of the extension): a new user key, with
which the private key of the account, every item and every folder are encrypted again, and which
the new master password unlocks.

The app takes such a rotation with one change of its own. Once it has come to the device in the
vault folder, the local vault refuses the tokens that it issued before it: the next request of the
app is answered with a 401, and the refresh token that the app then asks for another token with is
refused (`invalid_grant`), which logs the account out, as upstream does when a session ends
(`AuthTokenManager`, `AuthRepositoryImpl.refreshAccessTokenSynchronously`). The app thus writes
nothing with the user key before, which it still has, and the new master password logs in to the
account with the new key.

That logout is a soft one, after which upstream keeps the PIN: the user key encrypted with it,
which is the key before. Kept, it would unlock the account to that key once the account is locked
again, after it was logged in to with the new master password, and the app would write with that
key, with a token that the local vault takes: an item that it then adds or changes cannot be
decrypted with the new key, but can be with an earlier master password. So a soft logout for an
`invalid_grant` does not keep the PIN (`UserLogoutManagerImpl.softLogout`), which has to be set
again. The local vault refuses a refresh token only for a rotation, or for an account that it no
longer has, as its tokens do not expire. The browser extension logs out for good in that case,
which does not keep the PIN either.

What the app changes before the rotation comes to it is lost, and an item that it adds then cannot
be decrypted with the new key: the files of the vault say with which key each version was written,
and one of the key before never replaces one of the new key. A version of the app whose local vault
does not know of rotated keys cannot use the account once its key was rotated, until it is updated
(see "Building").

## Retiring a device

Settings › Account security › Devices sharing the vault folder lists the devices that have a file in
the vault of the account: the installations of the app and of the browser extension that share the
vault folder, by their types and their names, and the ID that names their directory, with when each
last changed the vault, and this one first (`.../accountsecurity/vaultdevices/`). The type and the
name are those that the client names its device by to a Bitwarden server as it logs in:
"Mobile - Android" and the model of the phone for the app (`DeviceModelProvider`), and the type of
the extension, such as "Extension - Chrome", and the browser for the extension, as upstream shows
them (`.../managedevices/util/DeviceTypeExtensions.kt`). They tell a device that is still used, but
only to fill in passwords, from one that is gone for good, as neither has changed the vault for a
long time. The files of a device say them once it changed the vault, or synced, with this version of
the app or of the extension: until then, it is listed as an unknown device. Upstream has a screen of
devices of its own, which lists those that a Bitwarden server knows, behind a feature flag that is
off; this one is a row of its own, and is not behind it. The local vault answers what the screen
asks for (`GET local-vault/devices` and `POST local-vault/devices/{id}/retire`), through
`LocalVaultDevicesService`, which the app has from `BitwardenServiceClient` like the other services,
and `LocalVaultDevicesManager`.

A device that will not be used again, such as this app before its data was cleared, is retired
there: this device takes its file over into its own, the vault is put together without it from
then on, and the first device that syncs 90 days later deletes its directory
(`docs/LOCAL_VAULT_FORMAT.md` of `local-vault-rs`, "Retiring a device"). The vault stays the same.
Until then, the device is listed as retired, with the date that its directory is deleted after. A
device that turns out to be still in use is no longer retired once it changes the vault, or syncs a
day or more after it last looked through its file for what other devices replaced, which it also
does at its first sync after the app starts, and keeps what it changed: the screen says "within a
day" for that. A device whose newer file has not fully come to this one yet is not retired, and the
screen says to try again later. So does it for a device that stopped for good while it wrote its
file, or whose file a tool that syncs the folder damaged, which can never be retired: its directory
stays, with what it holds, as that of a device that is gone for good did before devices could be
retired. Once a device of an account was retired, a device that has a version of the app or of the
browser extension from before cannot use the account until it is updated.

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
| `network/.../BitwardenServiceClientImpl.kt`, `network/.../model/BitwardenServiceClientConfig.kt` | `localVaultFileSystem`: setting it is what turns the local server on. The local server is given `appIdProvider.uniqueAppId` as the ID of the device, and the model of the device as its name (`DeviceModelProvider`), unless `localVaultDeviceName` gives another one, as the tests do, which cannot read the model. |
| `network/.../BitwardenServiceClient.kt`, `network/.../BitwardenServiceClientImpl.kt` | `localVaultDevicesService`, the devices that share the vault folder. |
| `network/.../retrofit/RetrofitsImpl.kt` | Installs `LocalServerInterceptor` on each HTTP client. |
| `app/.../datasource/network/di/PlatformNetworkModule.kt` | Hands the file system and `LocalBaseUrlsProvider` to the network layer. |
| `app/.../auth/datasource/disk/AuthDiskSourceImpl.kt` | `uniqueAppId` is made under a lock, as it names the directory that the local vault writes the changes of the app to. |
| `app/.../manager/network/NetworkConnectionManagerImpl.kt` | The network that is always connected. |
| `app/.../ui/platform/feature/settings/other/` | The setting for the vault folder. |
| `app/.../ui/auth/feature/landing/LandingScreen.kt`, `.../startregistration/StartRegistrationScreen.kt`, `.../login/LoginScreen.kt`, `.../vaultunlock/VaultUnlockScreen.kt`, and their tests | Logging in on the device: no server is named, and the landing screen and the screen for creating an account have no selector of the environment. |
| `ui/.../components/account/BitwardenAccountSwitcher.kt`, `.../account/dialog/BitwardenLogoutConfirmationDialog.kt`, `.../account/dialog/BitwardenRemovalConfirmationDialog.kt`, `ui/src/testFixtures/.../BitwardenAccountSwitcherTestHelpers.kt` | The account switcher and its dialogs name no environment. |
| `app/.../ui/platform/feature/settings/accountsecurity/AccountSecurityScreen.kt`, `.../AccountSecurityViewModel.kt`, `.../AccountSecurityNavigation.kt`, `.../settings/SettingsNavigation.kt`, and the tests of the first two | The row of the encryption key settings, and the route to their screen (`.../accountsecurity/kdfsettings/`). The row of the devices that share the vault folder, and the route to their screen (`.../accountsecurity/vaultdevices/`). The row that changes the master password opens the screen of this fork (`.../accountsecurity/changemasterpassword/`) instead of the web vault. |
| `app/.../auth/repository/AuthRepositoryImpl.kt`, and its test | `validatePassword` checks the master password against the user key when the hash that it keeps is that of other KDF settings than the ones the vault was unlocked with. |
| `app/.../auth/manager/UserLogoutManagerImpl.kt`, and its test | `softLogout` does not keep the PIN when the refresh token was refused (`invalid_grant`), which the local vault does once the user key was rotated on another device, as the PIN unlocks the key before (see "A rotation of the user key on another device"). |
| `.github/workflows/android.yml` | Builds the local vault with the app, and runs the tests of the local vault and those of the parts of the app that this fork changed. |

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
format of the vault folder has to reach both before either of them writes it. A client of
version 2 that gets a file of a later version fails the requests of the account, and those that
look for an account by an email that no other account has, as ones that did not reach the server,
until it is updated: it does not log the account out, take the master password to be wrong, or
let the email be registered again. The account cannot be used on that client until then, so the
app is released with such a change before the browser extension, or at the same time, and not
after it: a browser can update the extension before the user has updated the app.

Rotating the user key is such a change as well: the file of the device that rotates it, and every
file that a device writes with the new key, is of version 4 (`LOCAL_VAULT_FORMAT.md`, "Rotating the
user key"), which a client from before cannot read. It cannot use the account until it is updated,
and nothing else goes wrong there.

Version 2, in which every device writes a directory of its own, was such a change. The first
write of an updated client took a vault of version 1, which the `vault.json` of an account held
whole, over into the file of its device. The local vault no longer reads version 1 since the
commit "drop v1 support" of `local-vault-rs`: an account whose `vault.json` still holds such a
vault fails the same way as one of a later version, until a build from before then takes it over
(`LOCAL_VAULT_FORMAT.md`, "The earlier layout").

The `vault.json` that says that an account was deleted (`LOCAL_VAULT_FORMAT.md`, "Deleting an
account") is a change of the same kind, the other way round. A client from before it takes such
an account for one of a layout that it does not know, which it leaves alone. But it deletes the
whole directory of an account that it deletes, which can leave behind the file of a device that
changed the vault while the deletion was on its way to it. An updated client takes that file
for an account whose other files are still on their way, for good: its requests, and those for
an email that no other account has, fail until the directory is deleted by hand. If the file that
is left holds the records of the account and of its master password, as that of the device that
registered the account does, the account is back instead, without the content of its attachments.

## Code map

| Path | Content |
|------|---------|
| `local-vault-rs/` | The local vault in Rust, a submodule: the route table, the handlers, the vault store, the rules that the app checks itself against, and its tests. |
| `network/build.gradle.kts` | Builds the local vault and generates its Kotlin. |
| `network/src/main/kotlin/com/bitwarden/network/local/` | `LocalServer`, which hands requests to the local vault, the interceptor, the file-system interface and its implementation on ordinary files, the copy of the accounts, and of the files of the app in the accounts that the other folder has, to another folder (`LocalVaultCopier`), and how a request fails that the local vault refused (`isBadRequest`). |
| `network/.../retrofit/RetrofitsImpl.kt` | Where the interceptor is installed on each HTTP client. |
| `app/src/main/kotlin/com/x8bit/bitwarden/data/platform/manager/localvault/` | The vault folder: the manager, the implementation of the file system on a folder the user chose, and the addresses that cannot be reached. The devices that share it, and retiring one of them (`LocalVaultDevicesManager`). |
| `app/.../datasource/network/di/PlatformNetworkModule.kt` | Where the app hands the file system and the addresses to the network layer. |
| `app/.../manager/network/NetworkConnectionManagerImpl.kt` | The network that is always connected. |
| `app/.../ui/platform/feature/settings/other/` | The setting for the vault folder. |
| `app/.../data/auth/manager/KdfSettingsManager.kt`, `KdfSettingsManagerImpl.kt`, `.../manager/di/KdfSettingsManagerModule.kt`, `.../manager/model/ChangeKdfResult.kt`, `app/.../data/auth/util/KdfSettingsConstants.kt` | Changing how the master key is derived, and the settings that it may be changed to. |
| `app/.../ui/platform/feature/settings/accountsecurity/kdfsettings/` | The screen of the encryption key settings. |
| `app/.../data/auth/manager/MasterPasswordManager.kt`, `MasterPasswordManagerImpl.kt`, `.../manager/di/MasterPasswordManagerModule.kt`, `.../manager/model/ChangeMasterPasswordResult.kt` | Changing the master password. |
| `app/.../ui/platform/feature/settings/accountsecurity/changemasterpassword/` | The screen that changes the master password. |
| `network/.../api/LocalVaultDevicesApi.kt`, `network/.../service/LocalVaultDevicesService.kt`, `LocalVaultDevicesServiceImpl.kt`, `network/.../model/LocalVaultDeviceJson.kt`, `LocalVaultDevicesResponseJson.kt` | The requests for the devices that share the vault folder, which only the local vault answers. |
| `app/.../ui/platform/feature/settings/accountsecurity/vaultdevices/` | The screen of the devices that share the vault folder, which retires one of them. |
| `network/src/test/kotlin/com/bitwarden/network/local/` | Tests of the local server as the app uses it: through the interceptor, with the API services of the app, for every request that the app can make, for its feature flags, and for its file system. How the local server behaves is tested in `local-vault-rs`. |
