/*
 * Copyright 2016 requery.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.requery.sql.type;

import io.requery.sql.Keyword;
import io.requery.sql.DelegateType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

public class RealType extends DelegateType<Double> {

    public RealType() {
        super(Double.class, Types.REAL);
    }

    @Override
    public Double fromResult(ResultSet results, int column) throws SQLException {
        return results.getDouble(column);
    }

    @Override
    public Keyword identifier() {
        return Keyword.REAL;
    }
}