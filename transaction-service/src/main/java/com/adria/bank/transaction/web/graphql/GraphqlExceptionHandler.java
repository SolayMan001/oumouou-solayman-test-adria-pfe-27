package com.adria.bank.transaction.web.graphql;

import java.util.Map;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

@Component
public class GraphqlExceptionHandler extends DataFetcherExceptionResolverAdapter {
    @Override
    protected GraphQLError resolveToSingleError(Throwable error, DataFetchingEnvironment environment) {
        int status = 500;
        String code = "INTERNAL_ERROR";
        if (error instanceof IllegalArgumentException || error instanceof jakarta.validation.ConstraintViolationException
                || error instanceof org.springframework.validation.BindException) { status = 400; code = "INVALID_REQUEST"; }
        if (error instanceof com.adria.bank.transaction.service.TransactionNotFoundException) { status = 404; code = "TRANSACTION_NOT_FOUND"; }
        if (error instanceof com.adria.bank.transaction.service.TransactionProcessingException processing) {
            status = processing.getStatus(); code = processing.getCode();
        }
        ErrorType type = status == 404 ? ErrorType.NOT_FOUND : status < 500 ? ErrorType.BAD_REQUEST : ErrorType.INTERNAL_ERROR;
        return GraphqlErrorBuilder.newError(environment).errorType(type)
                .message(status == 500 ? "Request could not be completed." : error.getMessage())
                .extensions(Map.of("code", code, "httpStatus", status)).build();
    }
}
