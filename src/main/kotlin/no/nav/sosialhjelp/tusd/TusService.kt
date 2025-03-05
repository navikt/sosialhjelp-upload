package no.nav.sosialhjelp.tusd

import HookType
import no.nav.sosialhjelp.tusd.dto.HookRequest

class TusService {
    fun preCreate(request: HookRequest) =
        {
            assert(request.Type == HookType.PRE_CREATE)
        }
}
