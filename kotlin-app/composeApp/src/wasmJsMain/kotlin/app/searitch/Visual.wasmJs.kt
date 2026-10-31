package app.searitch

/**
 * The browser build leaves this out on purpose.
 *
 * Reading pixels here means decoding the picture in the page's own canvas, and
 * a cross-origin image can't be read back unless every source sends
 * `Access-Control-Allow-Origin` — several of them don't, and the ones that do
 * would still fail silently for the rest. So the phone build is the one that
 * orders similar pictures by look; on the web the strip keeps the order the
 * subject search gave it, and [Visual.rank] reports `byLook = false` rather than
 * claiming something it didn't do.
 */
actual suspend fun probeLuminance(url: String, cols: Int, rows: Int): FloatArray? = null
