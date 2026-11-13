# Changelog

## [Unreleased: foldables]

### Added
- Foldables (Galaxy Z Fold and similar): the game and the launcher turn with the device on both the inner and the cover screen. Settings > Display > Orientation: Auto, Landscape or Portrait  
- Fit screen view (Settings > Display > Screen view): the game shows more of the world to take the screen's shape, up to 480x320, with no stretching. Zoom sets how close it is  
- Per-screen settings: the view, zoom, scaling, pixels, screen filter and touch controls are kept separately for the inner and cover screen, in landscape and in portrait. A screen you haven't used yet starts on Fit screen, zoomed further out on the inner screen and closer on the cover screen  
- Pause when folding or unfolding: tap, A or Start to resume (Settings > Display)  
- Pause behind the notification shade, resuming when it closes (Settings > Display)  
- The Lost Age: Hide characters outside the room now works in every room, not only the two rooms upstream checked (Settings > Display; Off, Checked rooms or Every room)  
- Material You look for the side menu and launcher, with colours from your wallpaper on Android 12 and later. The menu has two panes on big screens, slides up from the bottom on a portrait phone screen, and sits at the side in landscape  

### Fixed
- Held buttons are let go when the app loses focus (notification shade, pop-ups), so they no longer stay pressed  
- Folding or unfolding no longer risks restarting the game screen  
- Portrait controls layout is now saved  

## [0.5. (11-07-2026)]

### Added
- Spanish, Italian, French and German versions of both games. Add the ROM and it shows up in the launcher; each language has its own game build, save and save states  
- Saves page: copy your USA/Europe save into another language  
- Enemy HP bar and Custom Battle Mode switches (Settings > Game)  
- Screen color: David's OG GBA  
- Launcher option to start your last game straight away, and Switch game in the menu to get back to the launcher  

### Changed
- Bumped to the latest GSRecomp version (0.7.1)  
- The game code is rebuilt once on first start after updating  

## [0.4.2. (10-26-2026)]

### Added
- Portrait mode: Settings > Display > Orientation. The picture sits at the top with the touch controls below it  
- Portrait controls (Settings > Controls, in portrait): Spread, Compact under the picture, or Compact at the bottom, with smaller buttons in the compact layouts  
- The game resizes properly in split screen and pop-up windows  

### Fixed
- Swiping the game away in recent apps left the game picker behind instead of closing the app  

## [0.4.1. (10-25-2026)]

### Fixed
- The Lost Age: with the CPU renderer, Expanded view now fills the screen with Stretch / Zoom (shows the normal picture; the wider view needs the GPU renderer)  
- The Lost Age: the same on the GPU renderer in places it can't draw the wider view, instead of black bars  
- The Lost Age: garbled character sprites with the CPU renderer in Expanded view  

## [0.4. (10-24-2026)]

### Added
- Golden Sun: The Lost Age support (same app; pick its ROM in the launcher)  
- Transfer your Golden Sun party to The Lost Age: "Load a Golden Sun save file?" on a new game uses this app's Golden Sun save or a .sav file you pick  
- Seamless mode: finishing Golden Sun continues straight into The Lost Age (asked on a new Golden Sun game when The Lost Age is installed)  
- Saves page in the menu: export and import .sav files (GBA emulator / flash cart saves), restore the save an import replaced, back up both games' saves to a folder  

### Changed
- Bumped to the latest GSRecomp version (0.6)  
- The game code is rebuilt once on first start after updating  
- Renderer: Auto uses the GPU renderer for The Lost Age in Expanded view  

## [0.3. (10-10-2026)]

### Changed
- Bumped to the latest GSRecomp version (0.5.3)  
- The game code is rebuilt once on first start after updating  
- Renderer: Auto now uses the CPU renderer (fast enough for Expanded view now); GPU is marked experimental  

### Fixed
- First-time build could freeze at 59% and never finish  
- The build log (Android/data/wootbeer.gsrandroid/files/gsr_build_log.txt) was never written  
- Expanded view ran slowly (now about as fast as Native)  
- Title and file select looked blurry in Expanded view  
- Screen tearing (a line across the picture), most visible when fast forwarding  
- Garbled colours beside the status screen in Expanded view  
- Black strip at the top of the sides in battles in Expanded view  

## [0.2. (10-9-2026)]

### Added
- Save states  
- Android version of Screen filters 
- Touch pad option: Joystick or D-pad  
- Change ROM option. Your save is kept  
- New screen colors from GSRecomp: Handheld, Handheld (lighter), Soft, Natural, Warm and Deep  

### Changed
- Bumped to the latest GSRecomp version (0.4.3)  
- ROM loader tidy up  

### Fixed
- First-time build could stop at 59% on slower devices  
- Stereo sound fixed  

## [0.1. (10-6-2026)]

First public release.  
This is an extreme beta-build.  

### Added
- Android support from Descore  
- ROM selection screen  
- On-device ROM / asset processing. The APK contains no Golden Sun code or assets  
- Renderer settings: Auto, CPU or GPU  
- Side menu  
- Display, audio, speed and touch options  
- View mode, scaling, filter, flicker reduction, frame interpolation  
- Touch control size and opacity  
- On-screen touch controls and physical controller support with button remapping  
- Local crash logging  

### Changed
- Video settings apply instantly  
- Moved cheats to side menu  

### Fixed
- Intro lens flare flicker. Sort-of, only proper on an LCD  
- Audio slowdown when the GPU renderer couldn't keep pace (handled by Auto falling back to CPU)  

### Removed
- Redundant options from in-game menu
