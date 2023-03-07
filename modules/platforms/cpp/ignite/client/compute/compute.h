/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include "ignite/client/network/cluster_node.h"
#include "ignite/client/primitive.h"
#include "ignite/client/transaction/transaction.h"
#include "ignite/common/config.h"
#include "ignite/common/ignite_result.h"

#include <memory>
#include <utility>

namespace ignite {

namespace detail {
class compute_impl;
}

/**
 * Ignite Compute facade.
 */
class compute {
    friend class ignite_client;

public:
    // Delete
    compute() = delete;

    /**
     * Executes single SQL statement asynchronously and returns rows.
     *
     * @param tx Optional transaction. If nullptr implicit transaction for this single operation is used.
     * @param statement Statement to execute.
     * @param args Arguments for the statement.
     * @param callback A callback called on operation completion with SQL result set.
     */
    IGNITE_API void execute_async(std::vector<cluster_node> nodes, std::string_view job_class_name, std::vector<primitive> args,
        ignite_callback<std::optional<primitive>> callback);

    /**
     * Executes single SQL statement and returns rows.
     *
     * @param tx Optional transaction. If nullptr implicit transaction for this single operation is used.
     * @param statement Statement to execute.
     * @param args Arguments for the statement.
     * @return SQL result set.
     */
    IGNITE_API std::optional<primitive> execute(std::vector<cluster_node> nodes, std::string_view job_class_name, std::vector<primitive> args) {
        return sync<std::optional<primitive>>([this, nodes = std::move(nodes), job_class_name, args = std::move(args)](auto callback) mutable {
            execute_async(std::move(nodes), job_class_name, std::move(args), std::move(callback));
        });
    }

private:
    /**
     * Constructor
     *
     * @param impl Implementation
     */
    explicit compute(std::shared_ptr<detail::compute_impl> impl)
        : m_impl(std::move(impl)) {}

    /** Implementation. */
    std::shared_ptr<detail::compute_impl> m_impl;
};

} // namespace ignite
