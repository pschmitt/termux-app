package com.termux.terminal;

import java.util.Arrays;
import java.util.HashMap;

/**
 * The hyperlinks of one {@link TerminalBuffer}, interned so that a terminal cell only has to store a
 * small `int` id (see {@link TerminalRow#getLinkId(int)}), where `0` means no link.
 * <p>
 * The table is bounded both by the number of distinct links and by the total number of characters
 * of all URIs, so a program can not make the terminal allocate unbounded memory by sending links.
 * When the table is full {@link #intern(TerminalHyperlink)} fails and returns `0`, which means the
 * text is displayed without a link. An id is only ever released by {@link #retainOnly(boolean[])}
 * for ids that no cell references anymore, so a cell can never end up pointing at a different link
 * than the one it was written with.
 * <p>
 * Not thread safe, the callers synchronize like they do for the rest of the buffer.
 */
final class TerminalHyperlinkRegistry {

    /** Max number of distinct links that can be referenced from the buffer at the same time. */
    static final int MAX_LINKS = 8192;

    /** Max total number of `chars` of all the URIs and ids in the table, which is about 2 MiB. */
    static final int MAX_TOTAL_CHARS = 1024 * 1024;

    /** Indexed by id, index `0` is unused. */
    private TerminalHyperlink[] mLinks = new TerminalHyperlink[16];
    private final HashMap<TerminalHyperlink, Integer> mIds = new HashMap<>();
    /** The highest id that has been handed out. */
    private int mMaxId = 0;
    /** Released ids that can be reused. */
    private int[] mFreeIds = new int[16];
    private int mFreeIdsCount = 0;
    private int mTotalChars = 0;

    /** Get the id of a link, adding it to the table if needed. Returns `0` if the table is full. */
    synchronized int intern(TerminalHyperlink link) {
        if (link == null) return 0;
        Integer existing = mIds.get(link);
        if (existing != null) return existing;

        int cost = chars(link);
        if (mIds.size() >= MAX_LINKS || mTotalChars + cost > MAX_TOTAL_CHARS) return 0;

        int id;
        if (mFreeIdsCount > 0) {
            id = mFreeIds[--mFreeIdsCount];
        } else {
            id = ++mMaxId;
            if (id >= mLinks.length) mLinks = Arrays.copyOf(mLinks, Math.min(mLinks.length * 2, MAX_LINKS + 1));
        }
        mLinks[id] = link;
        mIds.put(link, id);
        mTotalChars += cost;
        return id;
    }

    /** Get the link for an id, or `null` for `0` and unknown ids. */
    synchronized TerminalHyperlink get(int id) {
        return (id > 0 && id < mLinks.length) ? mLinks[id] : null;
    }

    /** The number of links in the table. */
    synchronized int size() {
        return mIds.size();
    }

    /** The highest id that has been handed out so far, which is the size needed for a `used` array. */
    synchronized int getMaxId() {
        return mMaxId;
    }

    /** Release all the ids for which `used[id]` is not `true`. Returns the number of ids released. */
    synchronized int retainOnly(boolean[] used) {
        int released = 0;
        for (int id = 1; id <= mMaxId; id++) {
            TerminalHyperlink link = mLinks[id];
            if (link == null || (id < used.length && used[id])) continue;
            mLinks[id] = null;
            mIds.remove(link);
            mTotalChars -= chars(link);
            if (mFreeIdsCount == mFreeIds.length) mFreeIds = Arrays.copyOf(mFreeIds, mFreeIds.length * 2);
            mFreeIds[mFreeIdsCount++] = id;
            released++;
        }
        return released;
    }

    private static int chars(TerminalHyperlink link) {
        return link.getUri().length() + (link.getId() == null ? 0 : link.getId().length());
    }

}
