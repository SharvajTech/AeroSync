package com.aerosync.security;

import com.aerosync.exception.AuthenticationRequiredException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets controller methods declare an {@link AuthenticatedUser} parameter.
 * Fails closed: without a principal set by {@link JwtFilter} the request is
 * rejected with 401 instead of running with no scope.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthenticatedUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object principal = webRequest.getAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (principal instanceof AuthenticatedUser user) {
            return user;
        }
        throw new AuthenticationRequiredException("Authentication required");
    }
}
