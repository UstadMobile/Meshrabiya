package com.ustadmobile.httpoverbluetooth.server

import java.util.UUID

fun interface OnUuidAllocatedListener {

    operator fun invoke(uuid: UUID)

}
