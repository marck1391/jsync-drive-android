# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Kotlin + Jetpack Compose (Material 3 components as a base, fully custom theme and components). A static Go node (the `jsync` mount client) runs as a child process and is driven over a local HTTP API; the UI never talks to the network itself.

## Users

The owner of a self-hosted jsync Drive (a personal homelab), confirmed as the first user. The user also wants the app to serve a team / other people, so it has to explain itself to people who did not set the server up: signing in is a device-code approval in the web app, the server and hub addresses are typed in once, and a private certificate authority may have to be trusted. Phones first; tablets are in scope too (confirmed). Situations: on the home Wi-Fi and away from it, checking or opening a file, sending photos from the phone, handing a file to another of their own devices.

## Product Purpose

Make a person's own Drive a native storage location on their phone: browse it, open and save files from any app (Android's document picker), upload from the share sheet, send files straight to their other devices, keep a phone folder in step with a Drive folder, and open end-to-end encrypted folders. Success: the Drive feels like part of the phone, not a separate website in an app, and nothing is ever lost or silently overwritten.

## Positioning

It is the same mount that `jsync mount drive` gives a desktop (encrypted session to the user's own hub, files fetched by range as they are read, end-to-end encrypted folders opened on the device with the vault password), plus device-to-device transfer over WebRTC with the web app's protocol. A consumer cloud app cannot truthfully claim "your files never leave your own server and your vault password never leaves the phone".

## Operating Context

- Connection states are real and frequent: not signed in, waiting for approval on another device, connecting to the hub, online, offline (retrying), read-only mount.
- Encrypted folders stay closed until the vault password is entered; they can lock themselves after a time.
- Transfers between devices need the other device online; requests arrive with the app closed (foreground service notification with Accept / Decline).
- Folder sync runs on a timer and on changes; conflicts keep both versions; a mass deletion is refused.
- A first-run login needs three addresses/inputs (Drive address, hub address, device name) and sometimes a CA certificate file.

## Capabilities and Constraints

- Screens that exist: Sign in (device code), Files (browse, upload, new folder, rename, delete, open with, unlock encrypted folder), Sync (phone folder <-> Drive folder), Devices (list, send, accept/decline, trusted devices, transfer progress), Settings (addresses, read-only, keep connected, certificate authorities, encrypted folders, sign out), the share target (to the Drive or to a device), a folder-unlock dialog opened from the system file picker.
- The Drive's file names, sizes and modification dates are all the metadata available; there are no thumbnails from the server (images would have to be fetched through the node).
- Android 8.0+ (minSdk 26), compileSdk 35. The system document picker draws the provider's name, icon and summary itself.
- Not decided: UI language (strings are English today; the user writes Spanish; a team audience suggests English + Spanish resources).

## Brand Commitments

Name: "jsync Drive". The existing web app is not binding: the user chose a distinct identity for the Android app. No logo exists for the app beyond a placeholder launcher icon.

## Evidence on Hand

- The web app's look (dark UI, orange-red accent, infinity logo) is evidence of the product's world, not a constraint.
- No user research, testimonials or usage data exist; do not invent any.

## Product Principles

1. The Drive is a place, not a feed: orientation (where am I, is it connected, is it safe) comes before decoration.
2. State is never hidden: offline, read-only, encrypted-and-locked, syncing and failed are always legible at a glance.
3. Nothing destructive happens by accident: deletes go to the trash and say so; conflicts keep both versions; a locked folder says why.
4. Private by construction, and the screen shows it: the vault password is checked on the phone; the UI never implies it is sent anywhere.
5. Works the same for the owner and for someone who never saw the server: every first-run step explains itself in one line.

## Accessibility & Inclusion

Standard Android expectations: TalkBack labels, 48dp touch targets, readable contrast in dark and light, respects system font scale and reduced motion. A team audience implies non-native English speakers, hence plain wording.
