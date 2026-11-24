# Nodstop privacy policy

Last updated: 23 November 2026

**Your trips never leave your phone.** Nodstop is an alarm that wakes you before your bus stop. Your GPS track during a
trip, your stops, learned rides and settings are kept only on your phone. Nodstop has no account and no server. It
doesn't send any of that to us or to anyone else, and it doesn't back it up to the cloud or copy it to a new phone.
The one exception is planning a trip: its start and end points go to public map services to find the walks (below).

## What stays on your phone

- **Location.** GPS is used only while a trip you started is running, to know when to wake you. Positions are kept in
  that trip's log in the app's own storage.
- **Your stops, routes and learned rides.** Kept in the app's own storage.
- **Chat notifications (While You Slept, off unless you turn it on).** During a trip, Nodstop can read your chat
  notifications to show what you missed on the alarm screen. They are kept in memory only. They're never saved,
  logged or sent anywhere, and they're gone when the trip ends.
- **Logs.** Trip logs stay on your phone. They leave it only when you tap Share and pick where they go, and the
  default share hides your location.

## What goes over the internet, and what it reveals

Nodstop makes only these requests. None of them carries your name, an account or your trips:

- **Map pictures** from OpenFreeMap (tiles.openfreemap.org), for the area shown on the map. Like any website, that
  server sees your IP address and which map area is loaded.
- **Plan a trip.** The planned trip's start and end points go to OpenStreetMap services to work out the walks. If you
  start from where you are, that start point is your current position. Two services are used:
  - Footpaths from Overpass (overpass-api.de, or its mirrors overpass.private.coffee and maps.mail.ru), for an area
    about 2 km across around each point.
  - Walking routes from FOSSGIS's foot router (routing.openstreetmap.de), for the exact points and the stops near
    them.
- **Google Maps short links** (maps.app.goo.gl) that you paste or share into Nodstop are opened with Google, to turn
  them into a place.

Bus stops and timetables come from Auckland Transport's public data, bundled inside the app. Looking them up sends
nothing.

## What we collect

Nothing. We run no server, use no analytics and no ads, and have no access to anything on your phone.

## Deleting your data

Uninstalling Nodstop deletes all of it. Settings → Debug → **Forget all learned stops** deletes learned rides.

## Children

Nodstop is meant for adults and is not directed at children.

## Changes and contact

Changes to this policy appear on this page, with its date. Questions: open an issue at
<https://github.com/tyforlife/wake-up-call/issues>, or use the contact email on Nodstop's Play Store page.
