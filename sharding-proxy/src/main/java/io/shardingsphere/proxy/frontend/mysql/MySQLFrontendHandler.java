/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
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
 * </p>
 */

package io.shardingsphere.proxy.frontend.mysql;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.shardingsphere.proxy.frontend.common.FrontendHandler;
import io.shardingsphere.proxy.transport.common.packet.DatabaseProtocolPacket;
import io.shardingsphere.proxy.transport.mysql.constant.StatusFlag;
import io.shardingsphere.proxy.transport.mysql.packet.MySQLPacketPayload;
import io.shardingsphere.proxy.transport.mysql.packet.command.CommandPacket;
import io.shardingsphere.proxy.transport.mysql.packet.command.CommandPacketFactory;
import io.shardingsphere.proxy.transport.mysql.packet.generic.ErrPacket;
import io.shardingsphere.proxy.transport.mysql.packet.generic.OKPacket;
import io.shardingsphere.proxy.transport.mysql.packet.handshake.ConnectionIdGenerator;
import io.shardingsphere.proxy.transport.mysql.packet.handshake.HandshakePacket;
import io.shardingsphere.proxy.transport.mysql.packet.handshake.HandshakeResponse41Packet;
import io.shardingsphere.proxy.transport.mysql.packet.handshake.ProxyAuthorityHandler;

/**
 * MySQL frontend handler.
 *
 * @author zhangliang
 * @author panjuan
 */
public final class MySQLFrontendHandler extends FrontendHandler {
    
    private final EventLoopGroup eventLoopGroup;
    
    private final ProxyAuthorityHandler proxyAuthorityHandler;
    
    public MySQLFrontendHandler(final EventLoopGroup eventLoopGroup) {
        this.eventLoopGroup = eventLoopGroup;
        proxyAuthorityHandler = new ProxyAuthorityHandler();
    }
    
    @Override
    protected void handshake(final ChannelHandlerContext context) {
        context.writeAndFlush(new HandshakePacket(ConnectionIdGenerator.getInstance().nextId(), proxyAuthorityHandler.getAuthPluginData()));
    }
    
    @Override
    protected void auth(final ChannelHandlerContext context, final ByteBuf message) {
        MySQLPacketPayload mysqlPacketPayload = new MySQLPacketPayload(message);
        try {
            HandshakeResponse41Packet response41 = new HandshakeResponse41Packet(mysqlPacketPayload);
            if (proxyAuthorityHandler.isLegalForProxyLogin(response41.getUsername(), response41.getAuthResponse())) {
                context.writeAndFlush(new OKPacket(response41.getSequenceId() + 1, 0L, 0L, StatusFlag.SERVER_STATUS_AUTOCOMMIT.getValue(), 0, ""));
            } else {
                context.writeAndFlush(new ErrPacket(response41.getSequenceId() + 1, 1045, "", "", "Access denied because of invalid username and password for Sharding Proxy."));
            }
        } finally {
            mysqlPacketPayload.getByteBuf().release();
        }
    }
    
    @Override
    protected void executeCommand(final ChannelHandlerContext context, final ByteBuf message) {
        ChannelThreadHolder.get(context.channel().id()).execute(new Runnable() {
            
            @Override
            public void run() {
                MySQLPacketPayload mysqlPacketPayload = new MySQLPacketPayload(message);
                try {
                    int sequenceId = mysqlPacketPayload.readInt1();
                    CommandPacket commandPacket = CommandPacketFactory.getCommandPacket(sequenceId, mysqlPacketPayload);
                    for (DatabaseProtocolPacket each : commandPacket.execute().getDatabaseProtocolPackets()) {
                        context.writeAndFlush(each);
                    }
                    while (commandPacket.hasMoreResultValue()) {
                        while (!context.channel().isWritable()) {
                            continue;
                        }
                        context.writeAndFlush(commandPacket.getResultValue());
                    }
                } finally {
                    mysqlPacketPayload.getByteBuf().release();
                }
            }
        });
    }
}
