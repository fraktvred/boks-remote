package dev.boksremote.protocol

import java.util.UUID

/** How a product is controlled: legacy GATT (+ RPC on firmware ≥ 3), bifrost RPC, or the Lightboks. */
enum class Family { LEGACY, BIFROST, LIGHT }

enum class ProductModel(val id: Int, val label: String, val family: Family, val mtuCap: Int) {
    SB3(1, "SOUNDBOKS Gen 3", Family.LEGACY, 96),
    SB_GO(2, "SOUNDBOKS Go", Family.LEGACY, 96),
    SB4(3, "SOUNDBOKS Gen 4", Family.LEGACY, 96),
    SB_MIX(4, "SOUNDBOKS Mix", Family.BIFROST, 500),
    SB_RIDER(5, "SOUNDBOKS Rider", Family.BIFROST, 500),
    SB7(6, "SOUNDBOKS (model 7)", Family.BIFROST, 500),
    SB8(7, "SOUNDBOKS (model 8)", Family.BIFROST, 500),
    LIGHTBOKS(124, "Lightboks", Family.LIGHT, 247);

    companion object {
        fun fromId(id: Int): ProductModel? = entries.firstOrNull { it.id == id }
    }
}

/** Security bitfield shared by adverts, SYSTEM_STATE and the legacy security characteristic. */
data class SecurityFlags(val pincodeEnabled: Boolean, val locked: Boolean) {
    companion object {
        fun from(byte: Int) = SecurityFlags(byte and 0x01 != 0, byte and 0x02 != 0)
    }
}

data class Advertisement(
    val address: String,
    val name: String?,
    val rssi: Int,
    val family: Family,
    val model: ProductModel?,
    val firmware: String?,
    val security: SecurityFlags?,
) {
    companion object {
        /**
         * [manufacturerData] is the payload Android returns per company ID (the ID itself removed):
         * security bitfield, firmware major/minor/patch, model id, then a family-specific tail.
         */
        fun parse(address: String, name: String?, rssi: Int, serviceUuids: List<UUID>, manufacturerData: ByteArray?): Advertisement? {
            val familyFromServices = when {
                Uuids.SPEAKER_CONTROL_SERVICE in serviceUuids -> Family.BIFROST
                Uuids.LIGHT_CONTROL_SERVICE in serviceUuids -> Family.LIGHT
                serviceUuids.any { it in Uuids.SCAN_SERVICES } -> Family.LEGACY
                else -> null
            }
            var model: ProductModel? = null
            var firmware: String? = null
            var security: SecurityFlags? = null
            if (manufacturerData != null && manufacturerData.size >= 5) {
                val b = { i: Int -> manufacturerData[i].toInt() and 0xFF }
                security = SecurityFlags.from(b(0))
                firmware = "${b(1)}.${b(2)}.${b(3)}"
                model = ProductModel.fromId(b(4)) ?: if (b(4) == 0) ProductModel.SB3 else null
            }
            // Trust the advertised service over the model byte when they disagree; the service is what we will talk to.
            val family = familyFromServices ?: model?.family ?: return null
            if (model != null && model.family != family) model = null
            return Advertisement(address, name, rssi, family, model, firmware, security)
        }
    }
}

/** True when the major component of an "a.b.c" version is at least [major]. */
fun versionAtLeast(version: String?, major: Int): Boolean =
    version?.substringBefore('.')?.toIntOrNull()?.let { it >= major } ?: false
