# SubRead Dictionary

A pop-up dictionary for Android that reads Yomitan dictionaries. Select a text
in any app and choose "SubRead Dictionary" in the text selection menu, share a
text to it, or send a word from
[SubRead Overlay](https://github.com/equwal/subread-overlay). The pop-up
opens over the app and shows each term at the start of the text, the longest
first, with the reading, the pitch accent, the frequency, and a button for
the audio. A tap on a character of the text looks up from there.

Nothing leaves the device, except when a remote audio source is on.

## How to use it

1. Import a dictionary: a Yomitan `.zip` (JMdict, Jitendex, a pitch accent
   dictionary, a frequency list). The app reads format 3. The order of the
   dictionaries in the settings is the order in the pop-up.
2. Local audio, optional: choose the `android.db` of the
   [Local Audio Server for Yomitan](https://github.com/yomidevs/local-audio-yomichan),
   the same file that AnkiConnect Android and Hoshi Reader use. The app copies
   it into its folder. Or push it there with `adb`; the settings show the
   folder. The order of the sources is a setting: `jpod, jpod_alternate,
   nhk16, shinmeikai8, forvo`.
3. Remote audio, optional and off by default: one URL per line, with `{term}`
   and `{reading}`. A URL can answer with an audio file (JapanesePod101 is
   the default line), or with the JSON list of a Local Audio Server on the
   network: `http://192.168.1.2:5050/?term={term}&reading={reading}`. The
   remote sources come after the local ones.

In the pop-up, "Play" plays the first source that has the word. A long press
on "Play" lists every source.

## How it works

`:core` is plain Kotlin, with no Android in it:

- `YomitanZip` reads the zip as a stream, one term at a time.
- `Deinflector` has the Japanese rules of Yomitan: 食べさせられた → 食べる,
  with the word class of each step checked against the rules of the term.
- `Lookup` tries each prefix of the text, the longest first, and each
  dictionary form of it, in one query.
- `Glossary` turns the structured content of a term into the simple HTML
  that a `TextView` shows.

`:app` has the SQLite store of the terms, the settings screen, the pop-up and
the audio.

## For other apps

Send a text with any of these; the pop-up opens over your app:

- `Intent.ACTION_PROCESS_TEXT` with `EXTRA_PROCESS_TEXT` (the text selection menu).
- `Intent.ACTION_SEND`, `text/plain`, with `EXTRA_TEXT` (the share sheet).
- The action `space.subread.dictionary.LOOKUP` with `EXTRA_TEXT`.

## Build

```
./gradlew :core:test :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest   # the SQLite store, on a device
```

The device test installs the debug app again and removes its data: the
imported dictionaries of the debug build are gone after it.

`-PplayStore=true` leaves the Ko-fi link out of the build for Google Play.

## Licence

AGPL-3.0. See `LICENSE`.
