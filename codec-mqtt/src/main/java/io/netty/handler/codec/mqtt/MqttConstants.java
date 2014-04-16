/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.handler.codec.mqtt;

import java.nio.charset.Charset;

public final class MqttConstants {

    public static final String PROTOCOL_NAME = "MQIsdp";
    public static final Charset UTF8_CHARSET = Charset.forName("UTF-8");

    public static final int QOS0 = 0;
    public static final int QOS1 = 1;
    public static final int QOS2 = 2;

    private MqttConstants() { }
}