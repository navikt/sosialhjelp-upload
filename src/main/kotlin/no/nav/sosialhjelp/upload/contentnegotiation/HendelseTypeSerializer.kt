package no.nav.sosialhjelp.upload.contentnegotiation

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import no.nav.sosialhjelp.upload.action.fiks.Vedlegg

class HendelseTypeSerializer : KSerializer<Vedlegg.HendelseType> {
    override val descriptor = PrimitiveSerialDescriptor("HendelseType", kotlinx.serialization.descriptors.PrimitiveKind.STRING)


    override fun serialize(encoder: Encoder, value: Vedlegg.HendelseType) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): Vedlegg.HendelseType {
        return Vedlegg.HendelseType.fromValue(decoder.decodeString())
    }
}
