import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class HookType {
    @SerialName("post-finish")
    PostFinish,

    @SerialName("post-terminate")
    PostTerminate,

    @SerialName("post-receive")
    PostReceive,

    @SerialName("post-create")
    PostCreate,

    @SerialName("pre-create")
    PreCreate,

    @SerialName("pre-finish")
    PreFinish,

    @SerialName("pre-terminate")
    PreTerminate,
}
