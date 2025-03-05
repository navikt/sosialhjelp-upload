import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class HookType {
    @SerialName("post-finish")
    POST_FINISH,

    @SerialName("post-terminate")
    POST_TERMINATE,

    @SerialName("post-receive")
    POST_RECEIVE,

    @SerialName("post-create")
    POST_CREATE,

    @SerialName("pre-create")
    PRE_CREATE,

    @SerialName("pre-finish")
    PRE_FINISH,

    @SerialName("pre-terminate")
    PRE_TERMINATE,
}
