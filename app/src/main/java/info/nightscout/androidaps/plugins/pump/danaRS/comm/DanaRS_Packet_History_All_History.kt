package info.nightscout.androidaps.plugins.pump.danaRS.comm

import info.nightscout.androidaps.plugins.pump.danaRS.encryption.BleEncryption
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.bus.RxBusWrapper

class DanaRS_Packet_History_All_History(
    aapsLogger: AAPSLogger,
    rxBus: RxBusWrapper,
    from: Long = 0
) : DanaRS_Packet_History_(aapsLogger, rxBus, from) {

    init {
        opCode = BleEncryption.DANAR_PACKET__OPCODE_REVIEW__ALL_HISTORY
        aapsLogger.debug(LTag.PUMPCOMM, "New message")
    }

    override fun getFriendlyName(): String {
        return "REVIEW__ALL_HISTORY"
    }
}