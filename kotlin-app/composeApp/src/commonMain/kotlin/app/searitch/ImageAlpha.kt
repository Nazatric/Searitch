package app.searitch

import coil3.Image

/**
 * Whether a decoded picture really carries see-through pixels.
 *
 * The file extension is a good hint but not proof — some sources send a
 * `content-type` that disagrees with the bytes, and a museum can hand back a
 * `.png` that is really a JPEG. Once the bitmap is decoded there is no guessing
 * left to do, so the detail view asks the decoder.
 *
 * `null` means "this platform cannot tell", and the caller falls back to the
 * format hint.
 */
expect fun Image.decodedAlpha(): Boolean?
