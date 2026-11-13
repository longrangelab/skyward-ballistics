# Skyward Ballistics

A ballistic calculator for Android, with a Windows version that shares the same engine.

## Features

- **Firing solutions** in MIL, MOA or inches per hundred yards, as turret clicks or holds: wind
  (with a bracket), look angle, cant, moving targets, spin drift, aerodynamic jump, Coriolis,
  powder temperature and the local atmosphere (station or corrected pressure, density altitude).
- **Drag** from G1 or G7 ballistic coefficients (several velocity bands) or from your own drag
  curves (Cd against Mach).
- **Your gear:** firearms, ammo and bullets, each ammo with its own zero and muzzle velocity.
- **Range cards and tables:** a full come-up table, target cards with several named targets,
  hit-probability estimates, and a round counter for each firearm.
- **Bring your existing profiles over:** Skyward can port your firearm, ammo and bullet profiles
  from Shooter, GeoBallistics and Empirical Precision.
- **Your data stays yours:** export everything or only the parts you choose, back up to a folder,
  sync through your own Google Drive, and restore earlier copies. Imports only add and update:
  the most recently edited copy wins.
- **Light and dark themes.**
- **Windows app** with the same calculations, profiles and backup files.

The app ships without a bullet library. Add your own bullets, or import bullet files you have.

## Check your shots

Skyward Ballistics gives predictions, not guarantees. Its answers are only as good as the numbers
you enter. Always confirm your data with live fire at known distances before you rely on it, and
follow safe firearm handling, your range's rules and your local laws. You use it at your own risk;
it comes with no warranty (see the license).

## Privacy

No accounts, no ads, no analytics, no tracking. Your data stays on your device unless you choose to
sync or share it. See [PRIVACY.md](PRIVACY.md).

## License

Copyright (C) 2026 longrangelab.

Skyward Ballistics is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, version 3 only
(GPL-3.0-only). It is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text.

## Building

Requirements: JDK 17 and the Android SDK (platform 35).

```bash
./gradlew :app:assembleDebug
```

The Windows app:

```bash
./gradlew :desktop:run
```
