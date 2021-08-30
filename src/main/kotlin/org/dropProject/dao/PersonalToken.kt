/*-
 * ========================LICENSE_START=================================
 * DropProject
 * %%
 * Copyright (C) 2019 Pedro Alves
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.dropProject.dao

import org.dropProject.services.AssignmentValidator
import java.util.*
import javax.persistence.*

/**
 * Enum that represents the status of a personal token.
 */
enum class TokenStatus {
    ACTIVE, DELETED
}

/**
 * Represents a personal token generated by a single user to grant access to the API on its behalf.
 *
 * @property id is a primary-key like generated value
 * @property userId is the user id (the same used for login)
 * @property personalToken is a random large unique String
 * @property status may be active, expired or deleted
 * @property statusDate the date of the last status update
 */
@Entity
data class PersonalToken(
    @Id @GeneratedValue
    val id: Long = 0,

    @Column(nullable = false, updatable = false)
    val userId: String,

    @Column(nullable = false, unique = true, updatable = false)
    val personalToken: String,

    @Column(nullable = false, updatable = false)
    val expirationDate: Date,

    @Column(nullable = false)
    var status: TokenStatus,

    @Column(nullable = false)
    var statusDate: Date,

    // comma-separated list of roles associated with this token
    @Column(nullable = false)
    var profiles: String
)
