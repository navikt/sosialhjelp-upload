package no.nav.sosialhjelp.progress

import io.ktor.util.reflect.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

val JsonSerializer: (TypeInfo, Any) -> String = { typeInfo, it ->
    Json.Default.encodeToString(Json.Default.serializersModule.serializer(typeInfo.kotlinType!!), it)
}
