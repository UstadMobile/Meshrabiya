package com.ustadmobile.meshrabiya.ext


fun Int.addressToDotNotation() : String {
    return "${(this shr 24).and(0xff)}.${(this shr 16).and(0xff)}" +
            ".${(this shr 8).and(0xff)}.${this.and(0xff)}"
}
