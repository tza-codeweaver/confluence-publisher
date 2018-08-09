package org.sahli.asciidoc.confluence.publisher.converter.providers;

import org.sahli.asciidoc.confluence.publisher.converter.providers.IncludeBasedAsciidocPagesStructureProvider.PathDelimiter;

import java.nio.file.Path;

public interface IncludeBasedAsciidocPagesStructureProviderListener {

    void processDirectory(Path directory);

    void processFile(Path file, Path targetFile);

    void collectInclude(String path, Path actualPath, Path resultPath, Path file, Path targetFile);

    void rejectInclude(String path, Path actualPath, Path file, Path targetFile);

    void processInclude(Path file, Path targetFile);

    void changePath(String path, Path actualPath, Path resultPath, Path file, Path targetFile, PathDelimiter delimiter);

    void missingPath(String path, Path actualPath, Path file, Path targetFile, PathDelimiter delimiter);
}
