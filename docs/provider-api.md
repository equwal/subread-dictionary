# The content provider of SubRead Dictionary

Another app can ask SubRead Dictionary for the terms of a text and for the audio of a term.
SubRead Anki does this for the definition, the reading and the word audio of a card. The
provider reads the dictionaries and the audio sources that the user set up in the app.

The authority is `space.subread.dictionary.lookup` (`space.subread.dictionary.debug.lookup`
for a debug build). On Android 11 and later, the manifest of the caller needs it in its
queries:

```xml
<queries>
    <provider android:authorities="space.subread.dictionary.lookup" />
</queries>
```

## Terms

```
content://space.subread.dictionary.lookup/terms?text=食べた&offset=0
```

One row per term that starts at `offset` in `text`, the longest first, then the dictionaries
in the order of the settings, then the higher score first. No row when nothing matches or no
dictionary is enabled.

| Column | Type | What |
|---|---|---|
| `expression` | text | The term as the dictionary writes it: `食べる`. |
| `reading` | text | The reading, in kana. The expression itself when the dictionary gives none. |
| `length` | integer | How many characters of the text, from the offset, the term covers. |
| `reasons` | text | The deinflection from the text to the term, separated by spaces: `past`. Empty for the dictionary form. |
| `dictionary` | text | The title of the dictionary. |
| `definition_html` | text | The glossary as simple HTML: `b`, `i`, `u`, `br`, `ul`, `li`, `a`. |
| `score` | real | The score of the term in its dictionary. |

## Audio

```
content://space.subread.dictionary.lookup/audio?expression=食べる&reading=たべる
```

One row when a source has the audio: the local sources in the order of the settings, then
the remote ones when they are on. No row when none has.

| Column | What |
|---|---|
| `file` | The name of the file in the cache of the app. |
| `mime` | `audio/mpeg`, `audio/ogg`, `audio/wav`, `audio/flac` or `audio/mp4`. |

The bytes are then at `content://space.subread.dictionary.lookup/audio/<file>`, to read with
`openInputStream`. The file stays for an hour.

```kotlin
val uri = Uri.parse("content://space.subread.dictionary.lookup/audio")
    .buildUpon().appendQueryParameter("expression", "食べる").appendQueryParameter("reading", "たべる").build()
val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
    if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("file")) else null
}
val bytes = name?.let { contentResolver.openInputStream(Uri.parse("content://space.subread.dictionary.lookup/audio/$it"))?.readBytes() }
```

The provider is read only: `insert`, `update` and `delete` throw.
