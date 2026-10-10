package io.homeassistant.companion.android.common.data.prefs

import kotlinx.serialization.Serializable

/**
 * A server the app removed because it was restored from a backup without its session, for example after moving to
 * a new device.
 *
 * It is kept so that widgets still configured for it can be pointed at the same server once the user signs in again.
 *
 * @property id the ID the server had before it was removed
 * @property urls the server's URLs, normalized so they can be compared with the URLs of signed in servers
 */
@Serializable
data class RemovedRestoredServer(val id: Int, val urls: Set<String>)
