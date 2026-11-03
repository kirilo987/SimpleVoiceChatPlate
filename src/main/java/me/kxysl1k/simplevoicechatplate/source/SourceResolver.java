package me.kxysl1k.simplevoicechatplate.source;

import java.io.File;
import java.util.concurrent.CompletableFuture;

public interface SourceResolver {

    boolean canHandle(String input);

    CompletableFuture<File> resolve(String input, File targetFile);
}
