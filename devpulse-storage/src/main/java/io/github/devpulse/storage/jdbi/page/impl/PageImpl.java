package io.github.devpulse.storage.jdbi.page.impl;

import io.github.devpulse.storage.jdbi.page.Page;
import io.github.devpulse.storage.jdbi.page.Pageable;
import java.io.Serial;
import java.util.List;

public class PageImpl<T> extends Chunk<T> implements Page<T> {

    @Serial private static final long serialVersionUID = 1L;

    private final long total;

    /**
     * Constructor of {@code PageImpl}.
     *
     * @param content the content of this page, must not be {@literal null}.
     * @param pageable the paging information, can be {@literal null}.
     * @param total the total amount of items available
     */
    public PageImpl(List<T> content, Pageable pageable, long total) {

        super(content, pageable);
        this.total = total;
    }

    /**
     * Creates a new {@link PageImpl} with the given content. This will result in the created {@link
     * Page} being identical to the entire {@link List}.
     *
     * @param content must not be {@literal null}.
     */
    public PageImpl(List<T> content) {
        this(content, null, null == content ? 0 : content.size());
    }

    @Override
    public int getTotalPages() {
        return getSize() == 0 ? 1 : (int) Math.ceil((double) total / (double) getSize());
    }

    @Override
    public long getTotalElements() {
        return total;
    }

    @Override
    public boolean hasNext() {
        return getNumber() < getTotalPages();
    }

    @Override
    public boolean isLast() {
        return !hasNext();
    }

    @Override
    public String toString() {
        String contentType = "UNKNOWN";
        List<T> content = getContent();
        if (!content.isEmpty()) {
            contentType = content.getFirst().getClass().getName();
        }
        return String.format(
                "Page %s of %d containing %s instances", getNumber(), getTotalPages(), contentType);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PageImpl<?> that)) {
            return false;
        }
        return this.total == that.total && super.equals(obj);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result += 31 * Long.hashCode(total);
        result += 31 * super.hashCode();
        return result;
    }
}
