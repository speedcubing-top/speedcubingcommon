package top.speedcubing.common.chatfilter;

public enum FilterTier {
    /** false-positive guard: a MASK/BLOCK hit lying entirely inside an ALLOW hit is ignored */
    ALLOW,
    /** replaced by '*' for receivers that enabled the chat filter */
    MASK,
    /** message is never delivered */
    BLOCK
}
