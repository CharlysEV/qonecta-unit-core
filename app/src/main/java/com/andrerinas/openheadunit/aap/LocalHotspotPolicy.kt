package com.andrerinas.openheadunit.aap

object LocalHotspotPolicy {
    fun preferredFiveGhzChannel(stationFrequency: Int?): Int =
        if (stationFrequency != null && stationFrequency in 5160..5895 && (stationFrequency - 5000) % 5 == 0)
            (stationFrequency - 5000) / 5 else 36

    /** Refuse ambiguous candidates rather than advertise a station/cellular address to the phone. */
    fun pick(
        candidates: List<ApInterfaceCandidate>,
        previousAddresses: Set<String>,
        upstreamInterfaces: Set<String> = emptySet(),
    ): ApInterfaceCandidate? {
        val standard = candidates.filter {
            it.isUp && !it.isLoopback && it.siteLocalIpv4 != null &&
                it.siteLocalIpv4 !in previousAddresses &&
                it.name !in upstreamInterfaces &&
                it.name.matches(Regex("(?:ap|wlan|swlan|softap)[0-9]+"))
        }.singleOrNull()
        if (standard != null) return standard

        // On head units like QTI Leapmotor C10 that have no wlan*/ap*/softap* interface in the kernel
        // at all and host LocalOnlyHotspot on p2p0 (ACTIVE_LOCAL_ONLY=[p2p0]), allow p2p0.
        val hasAnyStandardWifiIface = candidates.any {
            it.name.matches(Regex("(?:ap|wlan|swlan|softap)[0-9]+"))
        }
        if (!hasAnyStandardWifiIface) {
            return candidates.filter {
                it.isUp && !it.isLoopback && it.siteLocalIpv4 != null &&
                    it.name !in upstreamInterfaces &&
                    it.name.matches(Regex("p2p[0-9]+"))
            }.singleOrNull()
        }
        return null
    }

    fun eui64Mac(bytes: ByteArray): String? {
        if (bytes.size != 16 || bytes[0] != 0xfe.toByte() || bytes[1].toInt() and 0xc0 != 0x80 ||
            bytes[11] != 0xff.toByte() || bytes[12] != 0xfe.toByte()) return null
        val mac = byteArrayOf((bytes[8].toInt() xor 2).toByte(), bytes[9], bytes[10], bytes[13], bytes[14], bytes[15])
            .joinToString(":") { "%02x".format(it.toInt() and 255) }
        return mac.takeIf(NativeCredentialsPolicy::isUsableBssid)
    }
}
