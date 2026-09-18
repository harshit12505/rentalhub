package com.rentalhub.web.graphql;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * The three scalars GraphQL does not have built in.
 *
 * <b>Decimal travels as a string.</b> GraphQL's own Float is a double, which is exactly the
 * type this project bans for money (see MoneyMathTest), and a JSON number is read as a double
 * by most clients anyway. "12000.00" as a string survives every hop exactly. On the way in a
 * number is accepted too — it is turned into a BigDecimal from its text, never via a double.
 */
public final class GraphQlScalars {

    public static final GraphQLScalarType DECIMAL = GraphQLScalarType.newScalar()
            .name("Decimal")
            .description("An exact decimal, sent as a string.")
            .coercing(new DecimalCoercing())
            .build();

    public static final GraphQLScalarType DATE = GraphQLScalarType.newScalar()
            .name("Date")
            .description("An ISO-8601 date.")
            .coercing(new DateCoercing())
            .build();

    public static final GraphQLScalarType DATE_TIME = GraphQLScalarType.newScalar()
            .name("DateTime")
            .description("An ISO-8601 instant, in UTC.")
            .coercing(new DateTimeCoercing())
            .build();

    private GraphQlScalars() {
    }

    private static final class DecimalCoercing implements Coercing<BigDecimal, String> {

        @Override
        public String serialize(Object value, GraphQLContext context, Locale locale) {
            if (value instanceof BigDecimal decimal) {
                return decimal.toPlainString();
            }
            throw new CoercingSerializeException("Not a BigDecimal: " + value);
        }

        @Override
        public BigDecimal parseValue(Object input, GraphQLContext context, Locale locale) {
            if (input instanceof String || input instanceof Number) {
                try {
                    // From the text, never through a double.
                    return new BigDecimal(input.toString().strip());
                } catch (NumberFormatException notANumber) {
                    throw new CoercingParseValueException("Not a decimal number: " + input);
                }
            }
            throw new CoercingParseValueException("Not a decimal number: " + input);
        }

        @Override
        public BigDecimal parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context,
                                       Locale locale) {
            try {
                return switch (input) {
                    case StringValue text -> new BigDecimal(text.getValue().strip());
                    case IntValue whole -> new BigDecimal(whole.getValue());
                    case FloatValue decimal -> decimal.getValue();
                    default -> throw new CoercingParseLiteralException("Not a decimal number: " + input);
                };
            } catch (NumberFormatException notANumber) {
                throw new CoercingParseLiteralException("Not a decimal number: " + input);
            }
        }
    }

    private static final class DateCoercing implements Coercing<LocalDate, String> {

        @Override
        public String serialize(Object value, GraphQLContext context, Locale locale) {
            if (value instanceof LocalDate date) {
                return date.toString();
            }
            throw new CoercingSerializeException("Not a LocalDate: " + value);
        }

        @Override
        public LocalDate parseValue(Object input, GraphQLContext context, Locale locale) {
            try {
                return LocalDate.parse(input.toString());
            } catch (DateTimeParseException notADate) {
                throw new CoercingParseValueException("Not an ISO date (yyyy-mm-dd): " + input);
            }
        }

        @Override
        public LocalDate parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context,
                                      Locale locale) {
            if (input instanceof StringValue text) {
                try {
                    return LocalDate.parse(text.getValue());
                } catch (DateTimeParseException notADate) {
                    throw new CoercingParseLiteralException("Not an ISO date (yyyy-mm-dd): " + text.getValue());
                }
            }
            throw new CoercingParseLiteralException("A date must be a string: " + input);
        }
    }

    private static final class DateTimeCoercing implements Coercing<Instant, String> {

        @Override
        public String serialize(Object value, GraphQLContext context, Locale locale) {
            if (value instanceof Instant instant) {
                return instant.toString();
            }
            throw new CoercingSerializeException("Not an Instant: " + value);
        }

        @Override
        public Instant parseValue(Object input, GraphQLContext context, Locale locale) {
            try {
                return Instant.parse(input.toString());
            } catch (DateTimeParseException notAnInstant) {
                throw new CoercingParseValueException("Not an ISO instant: " + input);
            }
        }

        @Override
        public Instant parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context,
                                    Locale locale) {
            if (input instanceof StringValue text) {
                return parseValue(text.getValue(), context, locale);
            }
            throw new CoercingParseLiteralException("An instant must be a string: " + input);
        }
    }
}
