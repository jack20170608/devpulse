package io.github.devpulse.storage.jdbi.page.impl;

import io.github.devpulse.storage.jdbi.page.Direction;
import io.github.devpulse.storage.jdbi.page.Pageable;
import io.github.devpulse.storage.jdbi.page.Sort;
import java.io.Serial;
import java.util.Objects;

public class PageRequest extends AbstractPageRequest {

    @Serial private static final long serialVersionUID = 1L;

    private final Sort sort;

    public PageRequest(int page, int size) {
        this(page, size, null);
    }

    public PageRequest(int page, int size, Direction direction, String... properties) {
        this(page, size, new Sort(direction, properties));
    }

    public PageRequest(int page, int size, Sort sort) {
        super(page, size);
        this.sort = sort;
    }

    public Sort getSort() {
        return sort;
    }

    public Pageable next() {
        return new PageRequest(getPageNumber() + 1, getPageSize(), getSort());
    }

    public PageRequest previous() {
        return getPageNumber() == 1
                ? this
                : new PageRequest(getPageNumber() - 1, getPageSize(), getSort());
    }

    public Pageable first() {
        return new PageRequest(1, getPageSize(), getSort());
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PageRequest)) {
            return false;
        }
        PageRequest that = (PageRequest) obj;
        boolean sortEqual = Objects.equals(this.sort, that.sort);
        return super.equals(that) && sortEqual;
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + (null == sort ? 0 : sort.hashCode());
    }

    @Override
    public String toString() {
        return String.format(
                "Page request [number: %d, size %d, sort: %s]",
                getPageNumber(), getPageSize(), sort == null ? null : sort.toString());
    }
}
