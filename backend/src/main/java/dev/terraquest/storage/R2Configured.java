package dev.terraquest.storage;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * True when a full set of R2 credentials is configured; the switch that decides
 * whether the cloud or the local-filesystem {@link StorageProvider} is active.
 *
 * <p>Deliberately a {@code Condition} rather than {@code @ConditionalOnProperty}.
 * The credentials default to the empty string in {@code application.yml}
 * ({@code ${R2_ACCESS_KEY_ID:}}), and {@code @ConditionalOnProperty} treats a
 * present-but-empty property as a match -- which would wire up R2 with blank
 * credentials the moment the app runs with no cloud account. "Configured" here
 * means the values are actually non-blank.
 */
public class R2Configured implements Condition {

    static boolean isConfigured(Environment env) {
        return hasText(env, "terraquest.r2.account-id")
                && hasText(env, "terraquest.r2.access-key-id")
                && hasText(env, "terraquest.r2.secret-access-key");
    }

    private static boolean hasText(Environment env, String key) {
        return StringUtils.hasText(env.getProperty(key));
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isConfigured(context.getEnvironment());
    }

    /**
     * The exact complement of {@link R2Configured}. Used so the local provider
     * activates precisely when R2 does not, with no dependence on bean
     * registration order (as {@code @ConditionalOnMissingBean} would have).
     */
    public static final class NotConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !isConfigured(context.getEnvironment());
        }
    }
}
