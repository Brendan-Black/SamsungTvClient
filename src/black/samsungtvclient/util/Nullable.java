package black.samsungtvclient.util;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This value may be null, and the code using it has to say what happens then. Everything not marked is never null;
 * NullAway checks both in the build. A nullable record field comes with an {@code Optional} accessor.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE_USE)
public @interface Nullable {}
