package top.speedcubing.common.chatfilter;

import java.util.List;

public final class FilterResult {
    private final String masked;
    private final boolean blocked;
    private final boolean maskHit;
    private final List<String> blockedTerms;

    FilterResult(String clean) {
        this(clean, false, false, List.of());
    }

    FilterResult(String masked, boolean blocked, boolean maskHit, List<String> blockedTerms) {
        this.masked = masked;
        this.blocked = blocked;
        this.maskHit = maskHit;
        this.blockedTerms = blockedTerms;
    }

    public boolean isBlocked() {
        return blocked;
    }

    /** true if any MASK entry hit */
    public boolean isMasked() {
        return maskHit;
    }

    /** the message with MASK and BLOCK hits replaced by '*'; the same String instance when nothing hit */
    public String masked() {
        return masked;
    }

    /** the list entries that caused the block, for staff notices */
    public List<String> blockedTerms() {
        return blockedTerms;
    }
}
