package no.nav.sosialhjelp.tusd.dto

import kotlinx.serialization.Serializable

@Serializable
enum class HookType {
    HOOK_POST_FINISH,
    HOOK_POST_TERMINATE,
    HOOK_POST_RECEIVE,
    HOOK_POST_CREATE,
    HOOK_PRE_CREATE,
    HOOK_PRE_FINISH,
    HOOK_PRE_TERMINATE,
    ;

    override fun toString(): String = name.lowercase().replace("_", "-")
}
