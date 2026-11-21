package io.github.devpulse.storage.jdbi.page;

public interface Page<T> extends Slice<T> {

    int getTotalPages();

    long getTotalElements();

    int FIRST_PAGE = 1;
    int DEFAULT_PAGE_SIZE = 20;
}
