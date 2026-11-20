package com.hongguotv.nativeapp

/**
 * Encoders may pad a delivery size by a few pixels (S621's 1080P supply reports
 * 1922x1080, with 1282x720 and 962x540 on lower tiers). Probe the standard size
 * for that small alignment allowance; do not normalize portrait or genuinely
 * larger video into a supported landscape tier.
 */
private val alignedDeliverySizes =
    listOf(1920 to 1080,1280 to 720,960 to 540)

internal fun decoderProbeSize(width: Int,height: Int): Pair<Int,Int> {
    for ((standardWidth,standardHeight) in alignedDeliverySizes) {
        if(width in standardWidth..standardWidth+16 && height in standardHeight..standardHeight+8)
            return standardWidth to standardHeight
    }
    return width to height
}
