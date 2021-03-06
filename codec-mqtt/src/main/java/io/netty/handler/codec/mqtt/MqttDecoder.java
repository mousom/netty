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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.handler.codec.mqtt.MqttDecoder.DecoderState;
import io.netty.util.CharsetUtil;

import java.util.ArrayList;
import java.util.List;

import static io.netty.handler.codec.mqtt.MqttValidationUtil.*;
import static io.netty.handler.codec.mqtt.MqttCommonUtil.*;

import static io.netty.handler.codec.mqtt.MqttVersion.*;

/**
 * Decodes Mqtt messages from bytes, following
 * <a href="http://public.dhe.ibm.com/software/dw/webservices/ws-mqtt/mqtt-v3r1.html">
 *     the MQTT protocl specification v3.1</a>
 */
public class MqttDecoder extends ReplayingDecoder<DecoderState> {

    private static final int DEFAULT_MAX_BYTES_IN_MESSAGE = 8092;

    /**
     * States of the decoder.
     * We start at READ_FIXED_HEADER, followed by
     * READ_VARIABLE_HEADER and finally READ_PAYLOAD.
     */
    enum DecoderState {
        READ_FIXED_HEADER,
        READ_VARIABLE_HEADER,
        READ_PAYLOAD,
        BAD_MESSAGE,
    }

    private MqttFixedHeader mqttFixedHeader;
    private Object variableHeader;
    private Object payload;
    private int bytesRemainingInVariablePart;

    private final int maxBytesInMessage;

    public MqttDecoder() {
      this(DEFAULT_MAX_BYTES_IN_MESSAGE);
    }

    public MqttDecoder(int maxBytesInMessage) {
        super(DecoderState.READ_FIXED_HEADER);
        this.maxBytesInMessage = maxBytesInMessage;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
        switch (state()) {
            case READ_FIXED_HEADER:
                mqttFixedHeader = decodeFixedHeader(buffer);
                bytesRemainingInVariablePart = mqttFixedHeader.remainingLength();
                checkpoint(DecoderState.READ_VARIABLE_HEADER);
                // fall through

            case READ_VARIABLE_HEADER:  try {
                if (bytesRemainingInVariablePart > maxBytesInMessage) {
                    throw new DecoderException(
                            String.format(
                                    "Client tried to send very large message: %d bytes",
                                    bytesRemainingInVariablePart));
                }
                final Result<?> decodedVariableHeader = decodeVariableHeader(buffer, mqttFixedHeader);
                variableHeader = decodedVariableHeader.value;
                bytesRemainingInVariablePart -= decodedVariableHeader.numberOfBytesConsumed;
                checkpoint(DecoderState.READ_PAYLOAD);
                // fall through
            } catch (Exception cause) {
                out.add(invalidMessage(cause));
                return;
            }

            case READ_PAYLOAD: try {
                final Result<?> decodedPayload =
                        decodePayload(
                                buffer,
                                mqttFixedHeader.messageType(),
                                bytesRemainingInVariablePart,
                                variableHeader);
                payload = decodedPayload.value;
                bytesRemainingInVariablePart -= decodedPayload.numberOfBytesConsumed;
                if (bytesRemainingInVariablePart != 0) {
                    throw new DecoderException(
                            String.format(
                                    "Non-zero bytes remaining (%d) should never happen. "
                                            + "Message type: %s. Channel: %s",
                                    bytesRemainingInVariablePart,
                                    mqttFixedHeader.messageType(),
                                    ctx.channel()));
                }
                checkpoint(DecoderState.READ_FIXED_HEADER);
                MqttMessage message = MqttMessageFactory.create(mqttFixedHeader, variableHeader, payload);
                mqttFixedHeader = null;
                variableHeader = null;
                payload = null;
                out.add(message);
                break;
            } catch (Exception cause) {
                out.add(invalidMessage(cause));
                return;
            }

            case BAD_MESSAGE:
                // Keep discarding until disconnection.
                buffer.skipBytes(actualReadableBytes());
                break;

            default:
                throw new Error("Shouldn't reach here");
        }
    }

    private MqttMessage invalidMessage(Throwable cause) {
      checkpoint(DecoderState.BAD_MESSAGE);
      return MqttMessageFactory.createInvalidMessage(cause);
    }

    /**
     * Decodes the fixed header. It's one byte for the flags and then variable bytes for the remaining length.
     *
     * @param buffer the buffer to decode from
     * @return the fixed header
     */
    private static MqttFixedHeader decodeFixedHeader(ByteBuf buffer) {
        short b1 = buffer.readUnsignedByte();

        MqttMessageType messageType = MqttMessageType.valueOf(b1 >> 4);
        boolean dupFlag = (b1 & 0x08) == 0x08;
        int qosLevel = (b1 & 0x06) >> 1;
        boolean retain = (b1 & 0x01) != 0;

        int remainingLength = 0;
        int multiplier = 1;
        short digit;
        int loops = 0;
        do {
            digit = buffer.readUnsignedByte();
            remainingLength += (digit & 127) * multiplier;
            multiplier *= 128;
            loops++;
        } while ((digit & 128) != 0 && loops < 4);

        // MQTT protocol limits Remaining Length to 4 bytes
        if (loops == 4 && (digit & 128) != 0) {
            throw new DecoderException(
                    String.format(
                            "Failed to read fixed header of MQTT message. " +
                                    "It has more than 4 digits for Remaining Length. " +
                                    "MqttMessageType: %s.",
                            messageType));
        }
        MqttFixedHeader decodedFixedHeader =
                new MqttFixedHeader(messageType, dupFlag, QoS.valueOf(qosLevel), retain, remainingLength);
        return validateFixedHeader(resetUnusedFields(decodedFixedHeader));
    }

    /**
     * Decodes the variable header (if any)
     * @param buffer the buffer to decode from
     * @param mqttFixedHeader MqttFixedHeader of the same message
     * @return the variable header
     */
    private static Result<?> decodeVariableHeader(ByteBuf buffer, MqttFixedHeader mqttFixedHeader) {
        switch (mqttFixedHeader.messageType()) {
            case CONNECT:
                return decodeConnectionVariableHeader(buffer);

            case CONNACK:
                return decodeConnAckVariableHeader(buffer);

            case SUBSCRIBE:
            case UNSUBSCRIBE:
            case SUBACK:
            case UNSUBACK:
            case PUBACK:
            case PUBREC:
            case PUBCOMP:
            case PUBREL:
                return decodeMessageIdVariableHeader(buffer);

            case PUBLISH:
                return decodePublishVariableHeader(buffer, mqttFixedHeader);

            case PINGREQ:
            case PINGRESP:
            case DISCONNECT:
                // Empty variable header
                return new Result<Object>(null, 0);
        }
        return new Result<Object>(null, 0); //should never reach here
    }

    private static Result<MqttConnectVariableHeader> decodeConnectionVariableHeader(ByteBuf buffer) {
        final Result<String> protoString = decodeString(buffer);
        if (!PROTOCOL_NAME.equals(protoString.value)) {
            throw new DecoderException(PROTOCOL_NAME + " signature is missing. Closing channel.");
        }

        int numberOfBytesConsumed = protoString.numberOfBytesConsumed;

        final byte version = buffer.readByte();
        final int b1 = buffer.readUnsignedByte();
        numberOfBytesConsumed += 2;

        final Result<Integer> keepAlive = decodeMsbLsb(buffer);
        numberOfBytesConsumed += keepAlive.numberOfBytesConsumed;

        final boolean hasUserName = (b1 & 0x80) == 0x80;
        final boolean hasPassword = (b1 & 0x40) == 0x40;
        final boolean willRetain = (b1 & 0x20) == 0x20;
        final int willQos = (b1 & 0x18) >> 3;
        final boolean willFlag = (b1 & 0x04) == 0x04;
        final boolean cleanSession = (b1 & 0x02) == 0x02;

        final MqttConnectVariableHeader mqttConnectVariableHeader = new MqttConnectVariableHeader(
                PROTOCOL_NAME,
                version,
                hasUserName,
                hasPassword,
                willRetain,
                willQos,
                willFlag,
                cleanSession,
                keepAlive.value);
        return new Result<MqttConnectVariableHeader>(mqttConnectVariableHeader, numberOfBytesConsumed);
    }

    private static Result<MqttConnAckVariableHeader> decodeConnAckVariableHeader(ByteBuf buffer) {
        buffer.readUnsignedByte(); // reserved byte
        byte returnCode = buffer.readByte();
        final int numberOfBytesConsumed = 2;
        final MqttConnAckVariableHeader mqttConnAckVariableHeader =
                new MqttConnAckVariableHeader(MqttConnectReturnCode.valueOf(returnCode));
        return new Result<MqttConnAckVariableHeader>(mqttConnAckVariableHeader, numberOfBytesConsumed);
    }

    private static Result<MqttMessageIdVariableHeader> decodeMessageIdVariableHeader(ByteBuf buffer) {
        final Result<Integer> messageId = decodeMessageId(buffer);
        return new Result<MqttMessageIdVariableHeader>(
                MqttMessageIdVariableHeader.from(messageId.value),
                messageId.numberOfBytesConsumed);
    }

    private static Result<MqttPublishVariableHeader> decodePublishVariableHeader(
            ByteBuf buffer,
            MqttFixedHeader mqttFixedHeader) {
        final Result<String> decodedTopic = decodeString(buffer);
        if (!isValidPublishTopicName(decodedTopic.value)) {
            throw new DecoderException(String.format("Publish topic name %s contains wildcards.", decodedTopic.value));
        }
        int numberOfBytesConsumed = decodedTopic.numberOfBytesConsumed;

        int messageId = -1;
        if (mqttFixedHeader.qosLevel().value() > 0) {
            final Result<Integer> decodedMessageId = decodeMessageId(buffer);
            messageId = decodedMessageId.value;
            numberOfBytesConsumed += decodedMessageId.numberOfBytesConsumed;
        }
        final MqttPublishVariableHeader mqttPublishVariableHeader =
                new MqttPublishVariableHeader(decodedTopic.value, messageId);
        return new Result<MqttPublishVariableHeader>(mqttPublishVariableHeader, numberOfBytesConsumed);
    }

    private static Result<Integer> decodeMessageId(ByteBuf buffer) {
        final Result<Integer> messageId = decodeMsbLsb(buffer);
        if (!isValidMessageId(messageId.value)) {
            throw new DecoderException(String.format("Invalid messageId %d", messageId.value));
        }
        return messageId;
    }

    /**
     * Decodes the payload.
     *
     * @param buffer the buffer to decode from
     * @param messageType  type of the message being decoded
     * @param bytesRemainingInVariablePart bytes remaining
     * @param variableHeader variable header of the same message
     * @return the payload
     */
    private static Result<?> decodePayload(
            ByteBuf buffer,
            MqttMessageType messageType,
            int bytesRemainingInVariablePart,
            Object variableHeader) {
        switch (messageType) {
            case CONNECT:
                return decodeConnectionPayload(buffer, (MqttConnectVariableHeader) variableHeader);

            case SUBSCRIBE:
                return decodeSubscribePayload(buffer, bytesRemainingInVariablePart);

            case SUBACK:
                return decodeSubackPayload(buffer, bytesRemainingInVariablePart);

            case UNSUBSCRIBE:
                return decodeUnsubscribePayload(buffer, bytesRemainingInVariablePart);

            case PUBLISH:
                return decodePublishPayload(buffer, bytesRemainingInVariablePart);

            default:
                // unknown payload , no byte consumed
                return new Result<Object>(null, 0);
        }
    }

    private static Result<MqttConnectPayload> decodeConnectionPayload(
            ByteBuf buffer,
            MqttConnectVariableHeader mqttConnectVariableHeader) {
        final Result<String> decodedClientId = decodeString(buffer);
        final String decodedClientIdValue = decodedClientId.value;
        if (!isValidClientId(decodedClientIdValue)) {
            throw new DecoderException(
                    String.format("Invalid clientIdentifier %s ",
                                  decodedClientIdValue != null ? decodedClientIdValue : "null"));
        }
        int numberOfBytesConsumed = decodedClientId.numberOfBytesConsumed;

        Result<String> decodedWillTopic = null;
        Result<String> decodedWillMessage = null;
        if (mqttConnectVariableHeader.isWillFlag()) {
            decodedWillTopic = decodeString(buffer, 0, 32767);
            numberOfBytesConsumed += decodedWillTopic.numberOfBytesConsumed;
            decodedWillMessage = decodeAsciiString(buffer);
            numberOfBytesConsumed += decodedWillMessage.numberOfBytesConsumed;
        }
        Result<String> decodedUserName = null;
        Result<String> decodedPassword = null;
        if (mqttConnectVariableHeader.hasUserName()) {
            decodedUserName = decodeString(buffer);
            numberOfBytesConsumed += decodedUserName.numberOfBytesConsumed;
        }
        if (mqttConnectVariableHeader.hasPassword()) {
            decodedPassword = decodeString(buffer);
            numberOfBytesConsumed += decodedPassword.numberOfBytesConsumed;
        }

        final MqttConnectPayload mqttConnectPayload =
                new MqttConnectPayload(
                        decodedClientId.value,
                        decodedWillTopic.value,
                        decodedWillMessage.value,
                        decodedUserName.value,
                        decodedPassword.value);
        return new Result<MqttConnectPayload>(mqttConnectPayload, numberOfBytesConsumed);
    }

    private static Result<MqttSubscribePayload> decodeSubscribePayload(
            ByteBuf buffer,
            int bytesRemainingInVariablePart) {
        final List<MqttTopicSubscription> subscribeTopics = new ArrayList<MqttTopicSubscription>();
        int numberOfBytesConsumed = 0;
        while (numberOfBytesConsumed < bytesRemainingInVariablePart) {
            final Result<String> decodedTopicName = decodeString(buffer);
            numberOfBytesConsumed += decodedTopicName.numberOfBytesConsumed;
            int qos = buffer.readUnsignedByte() & 0x03;
            numberOfBytesConsumed++;
            subscribeTopics.add(new MqttTopicSubscription(decodedTopicName.value, QoS.valueOf(qos)));
        }
        return new Result<MqttSubscribePayload>(new MqttSubscribePayload(subscribeTopics), numberOfBytesConsumed);
    }

    private static Result<MqttSubAckPayload> decodeSubackPayload(
            ByteBuf buffer,
            int bytesRemainingInVariablePart) {
        final List<Integer> grantedQos = new ArrayList<Integer>();
        int numberOfBytesConsumed = 0;
        while (numberOfBytesConsumed < bytesRemainingInVariablePart) {
            int qos = buffer.readUnsignedByte() & 0x03;
            numberOfBytesConsumed++;
            grantedQos.add(qos);
        }
        return new Result<MqttSubAckPayload>(new MqttSubAckPayload(grantedQos), numberOfBytesConsumed);
    }

    private static Result<MqttUnsubscribePayload> decodeUnsubscribePayload(
            ByteBuf buffer,
            int bytesRemainingInVariablePart) {
        final List<String> unsubscribeTopics = new ArrayList<String>();
        int numberOfBytesConsumed = 0;
        while (numberOfBytesConsumed < bytesRemainingInVariablePart) {
            final Result<String> decodedTopicName = decodeString(buffer);
            numberOfBytesConsumed += decodedTopicName.numberOfBytesConsumed;
            unsubscribeTopics.add(decodedTopicName.value);
        }
        return new Result<MqttUnsubscribePayload>(
                new MqttUnsubscribePayload(unsubscribeTopics),
                numberOfBytesConsumed);
    }

    private static Result<ByteBuf> decodePublishPayload(ByteBuf buffer, int bytesRemainingInVariablePart) {
        ByteBuf b = buffer.readSlice(bytesRemainingInVariablePart).retain();
        return new Result<ByteBuf>(b, bytesRemainingInVariablePart);
    }

    private static Result<String> decodeString(ByteBuf buffer) {
        return decodeString(buffer, 0, Integer.MAX_VALUE);
    }

    private static Result<String> decodeAsciiString(ByteBuf buffer) {
        Result<String> result = decodeString(buffer, 0, Integer.MAX_VALUE);
        final String s = result.value;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return new Result<String>(null, result.numberOfBytesConsumed);
            }
        }
        return new Result<String>(s, result.numberOfBytesConsumed);
    }

    private static Result<String> decodeString(ByteBuf buffer, int minBytes, int maxBytes) {
        final Result<Integer> decodedSize = decodeMsbLsb(buffer);
        int size = decodedSize.value;
        int numberOfBytesConsumed = decodedSize.numberOfBytesConsumed;
        if (size < minBytes || size > maxBytes) {
            buffer.skipBytes(size);
            numberOfBytesConsumed += size;
            return new Result<String>(null, numberOfBytesConsumed);
        }
        ByteBuf buf = buffer.readBytes(size);
        numberOfBytesConsumed += size;
        return new Result<String>(buf.toString(CharsetUtil.UTF_8), numberOfBytesConsumed);
    }

    private static Result<Integer> decodeMsbLsb(ByteBuf buffer) {
        return decodeMsbLsb(buffer, 0, 65535);
    }

    private static Result<Integer> decodeMsbLsb(ByteBuf buffer, int min, int max) {
        short msbSize = buffer.readUnsignedByte();
        short lsbSize = buffer.readUnsignedByte();
        final int numberOfBytesConsumed = 2;
        int result = msbSize << 8 | lsbSize;
        if (result < min || result > max) {
            result = -1;
        }
        return new Result<Integer>(result, numberOfBytesConsumed);
    }

    private static final class Result<T> {

        private final T value;
        private final int numberOfBytesConsumed;

        Result(T value, int numberOfBytesConsumed) {
            this.value = value;
            this.numberOfBytesConsumed = numberOfBytesConsumed;
        }
    }
}
