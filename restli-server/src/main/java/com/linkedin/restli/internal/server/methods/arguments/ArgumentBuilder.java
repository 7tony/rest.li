/*
   Copyright (c) 2012 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

/**
 * $Id: $
 */

package com.linkedin.restli.internal.server.methods.arguments;


import com.linkedin.data.DataList;
import com.linkedin.data.schema.validation.CoercionMode;
import com.linkedin.data.schema.validation.RequiredMode;
import com.linkedin.data.schema.validation.ValidateDataAgainstSchema;
import com.linkedin.data.schema.validation.ValidationOptions;
import com.linkedin.data.template.AbstractArrayTemplate;
import com.linkedin.data.template.DataTemplate;
import com.linkedin.data.template.DataTemplateUtil;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.restli.common.HttpStatus;
import com.linkedin.restli.internal.server.model.Parameter;
import com.linkedin.restli.internal.server.util.ArgumentUtils;
import com.linkedin.restli.internal.server.util.RestUtils;
import com.linkedin.restli.server.PagingContext;
import com.linkedin.restli.server.ResourceContext;
import com.linkedin.restli.server.RoutingException;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;

/**
 * @author Josh Walker
 * @version $Revision: $
 */

public class ArgumentBuilder
{

  /**
   * Build arguments for resource method invocation. Combines various types of arguments
   * into a single array.
   *
   * @param positionalArguments pass-through arguments coming from
   *          {@link RestLiArgumentBuilder}
   * @param parameters list of request {@link Parameter}s
   * @param context {@link ResourceContext}
   * @return array of method argument for method invocation.
   */
  public static Object[] buildArgs(final Object[] positionalArguments,
                                   final List<Parameter<?>> parameters,
                                   final ResourceContext context)
  {
    Object[] arguments = Arrays.copyOf(positionalArguments, parameters.size());

    for (int i = positionalArguments.length; i < parameters.size(); ++i)
    {
      Parameter<?> param = parameters.get(i);
      if (param.getParamType() == Parameter.ParamType.KEY)
      {
        Object value = context.getPathKeys().get(param.getName());
        if (value == null && param.isOptional() == false)
        {
          throw new RoutingException("Association key '" + param.getName()
              + "' is required", HttpStatus.S_400_BAD_REQUEST.getCode());
        }
        arguments[i] = value;
      }
      else if (param.getParamType() == Parameter.ParamType.CALLBACK)
      {
        continue;
      }
      else if (param.getParamType() == Parameter.ParamType.PARSEQ_CONTEXT)
      {
        continue; // don't know what to fill in yet
      }
      else if (param.getType().isAssignableFrom(PagingContext.class))
      {
        PagingContext ctx =
            RestUtils.getPagingContext(context, (PagingContext) param.getDefaultValue());
        arguments[i] = ctx;
      }
      else if (DataTemplate.class.isAssignableFrom(param.getType()))
      {
        arguments[i] = buildDataTemplateArgument(context, param);
      }
      else
      {
        arguments[i] = buildRegularArgument(context, param);
      }
    }
    return arguments;
  }

  /**
   * Build a method argument from a request parameter that is an array
   *
   * @param context {@link ResourceContext}
   * @param param {@link Parameter}
   * @return argument value in the correct type
   */
  private static Object buildArrayArgument(final ResourceContext context,
                                           final Parameter<?> param)
  {
    final Object convertedValue;
    if (DataTemplate.class.isAssignableFrom(param.getItemType()))
    {
      final DataList itemsList = (DataList) context.getStructuredParameter(param.getName());
      convertedValue = Array.newInstance(param.getItemType(), itemsList.size());
      int j = 0;
      for (Object paramData: itemsList)
      {
        final DataTemplate<?> itemsElem = DataTemplateUtil.wrap(paramData, param.getItemType().asSubclass(DataTemplate.class));
        Array.set(convertedValue, j++, itemsElem);
      }
    }
    else
    {
      final List<String> itemStringValues = context.getParameterValues(param.getName());
      convertedValue = Array.newInstance(param.getItemType(), itemStringValues.size());
      int j = 0;
      for (String itemStringValue : itemStringValues)
      {
        if (itemStringValue == null)
        {
          throw new RoutingException("Parameter '" + param.getName()
                                         + "' cannot contain null values", HttpStatus.S_400_BAD_REQUEST.getCode());
        }
        Array.set(convertedValue,
                  j++,
                  ArgumentUtils.convertSimpleValue(itemStringValue,
                                                   param.getDataSchema(),
                                                   param.getItemType(),
                                                   false));
      }
    }

    return convertedValue;
  }

  /**
   * Build a method argument from a request parameter that is NOT backed by a schema, i.e.
   * a primitive or an array
   *
   * @param context {@link ResourceContext}
   * @param param {@link Parameter}
   * @return argument value in the correct type
   */
  private static Object buildRegularArgument(final ResourceContext context,
                                             final Parameter<?> param)
  {
    String value =
        ArgumentUtils.argumentAsString(context.getParameter(param.getName()),
                                       param.getName());

    final Object convertedValue;
    if (value == null)
    {
      if (param.isOptional() && param.hasDefaultValue())
      {
        convertedValue = param.getDefaultValue();
      }
      else if (param.isOptional() && !param.getType().isPrimitive())
      {
        convertedValue = null;
      }
      else
      {
        throw new RoutingException("Parameter '" + param.getName() + "' is required",
                                   HttpStatus.S_400_BAD_REQUEST.getCode());
      }
    }
    else
    {
      if (param.isArray())
      {
        convertedValue = buildArrayArgument(context, param);
      }
      else
      {
        convertedValue = ArgumentUtils.convertSimpleValue(value, param.getDataSchema(), param.getType(), false);
      }
    }

    return convertedValue;
  }

  private static DataTemplate<?> buildDataTemplateArgument(final ResourceContext context,
                                                        final Parameter<?> param)
  {
    Object paramValue = context.getStructuredParameter(param.getName());
    DataTemplate<?> paramRecordTemplate;

    if (paramValue == null)
    {
      if (!param.isOptional())
      {
        throw new RoutingException("Parameter '" + param.getName() + "' is required",
                                   HttpStatus.S_400_BAD_REQUEST.getCode());
      }

      if (!param.hasDefaultValue())
      {
        return null;
      }

      paramRecordTemplate = (DataTemplate) param.getDefaultValue();
    }
    else
    {
      @SuppressWarnings("unchecked")
      final Class<? extends RecordTemplate> paramType = (Class<? extends RecordTemplate>) param.getType();
      /**
       * It is possible for the paramValue provided by ResourceContext to be coerced to the wrong type.
       * If a query param is a single value param for example www.domain.com/resource?foo=1.
       * Then ResourceContext will parse foo as a String with value = 1.
       * However if a query param contains many values for example www.domain.com/resource?foo=1&foo=2&foo=3
       * Then ResourceContext will parse foo as an DataList with value [1,2,3]
       *
       * So this means if the 'final' type of a query param is an Array and the paramValue we received from
       * ResourceContext is not a DataList we will have to wrap the paramValue inside a DataList
       */
      if (AbstractArrayTemplate.class.isAssignableFrom(paramType) &&
          paramValue.getClass() != DataList.class)
      {
        paramRecordTemplate = DataTemplateUtil.wrap(new DataList(Arrays.asList(paramValue)), paramType);
      }
      else
      {
        paramRecordTemplate = DataTemplateUtil.wrap(paramValue, paramType);
      }
    }
    // Validate against the class schema with FixupMode.STRING_TO_PRIMITIVE to parse the
    // strings into the corresponding primitive types.
    ValidateDataAgainstSchema.validate(paramRecordTemplate.data(),
                                       paramRecordTemplate.schema(),
                                       new ValidationOptions(RequiredMode.CAN_BE_ABSENT_IF_HAS_DEFAULT,
                                                             CoercionMode.STRING_TO_PRIMITIVE));
    return paramRecordTemplate;
  }
}
