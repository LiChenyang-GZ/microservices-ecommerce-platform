package com.comp5348.delivery.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Only an admin may call this controller (or method). Checked by AdminOnlyInterceptor after Spring has picked the
 * handler, so it holds however the path was written (auth PR 3, review 47 🔴1).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminOnly {
}
