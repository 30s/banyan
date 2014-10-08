package com.freedom.messagebus.httpbridge.controller;

import com.freedom.messagebus.client.*;
import com.freedom.messagebus.client.model.MessageCarryType;
import com.freedom.messagebus.common.message.Message;
import com.freedom.messagebus.common.message.MessageJSONSerializer;
import com.freedom.messagebus.common.message.MessageType;
import com.freedom.messagebus.httpbridge.util.Consts;
import com.freedom.messagebus.httpbridge.util.ResponseUtil;
import com.google.gson.Gson;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.List;

public class HttpBridge extends HttpServlet {

    private static final Log  logger = LogFactory.getLog(HttpBridge.class);
    private static final Gson gson   = new Gson();

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
        logger.info("[service] url is : " + req.getRequestURI());
        String apiType = req.getParameter("type");

        if (apiType == null || apiType.isEmpty()) {
            logger.error("the query string : type can not be null or empty");
            ResponseUtil.response(resp, Consts.HTTP_FAILED_CODE,
                                  "the query string : type can not be null or empty",
                                  "", "");
        } else {
            MessageCarryType msgCarryType = MessageCarryType.lookup(apiType);

            switch (msgCarryType) {
                case PRODUCE:
                    this.produce(req, resp);
                    break;

                case CONSUME:
                    this.consume(req, resp);
                    break;

                case REQUEST:
                    this.request(req, resp);
                    break;

                case RESPONSE:
                    this.response(req, resp);
                    break;

                default:
                    ResponseUtil.response(resp, Consts.HTTP_FAILED_CODE,
                                          "invalidate type", "", "");
            }
        }
    }

    private void produce(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
        if (!request.getMethod().toLowerCase().equals("post")) {
            logger.error("[produce] error http request method");
            ResponseUtil.response(response, Consts.HTTP_FAILED_CODE,
                                  "error http request method", "", "");
        } else {
            Messagebus messagebus = (Messagebus) (getServletContext().getAttribute(Consts.MESSAGE_BUS_KEY));
            String queueName = request.getRequestURI().split("/")[3];
            String msgArrStr = request.getParameter("messages");

            if (msgArrStr == null || msgArrStr.isEmpty()) {
                logger.error("[produce] param : messages can not be null or empty");
                ResponseUtil.response(response, Consts.HTTP_FAILED_CODE,
                                      "param : messages can not be null or empty", "", "''");
            } else {
                Message[] msgArr = MessageJSONSerializer.deSerializeMessages(msgArrStr, MessageType.AppMessage);

                try {
                    IProducer producer = messagebus.getProducer();
                    producer.batchProduce(msgArr, queueName);
                    ResponseUtil.response(response, Consts.HTTP_SUCCESS_CODE, "", "", "''");
                } catch (MessagebusUnOpenException e) {
                    logger.error("[produce] occurs a MessagebusUnOpenException : " + e.getMessage());
                    ResponseUtil.response(response,
                                          Consts.HTTP_FAILED_CODE,
                                          "[produce] occurs a MessagebusUnOpenException : " + e.getMessage(),
                                          "",
                                          "''");
                }
            }
        }
    }

    private void consume(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
        if (!request.getMethod().toLowerCase().equals("get")) {
            logger.error("[consume] error http request method");
            ResponseUtil.response(response, Consts.HTTP_FAILED_CODE,
                                  "error http request method", "", "''");
        } else {
            Messagebus messagebus = (Messagebus) (getServletContext().getAttribute(Consts.MESSAGE_BUS_KEY));
            String mode = request.getParameter("mode");

            if (mode == null || mode.isEmpty()) {
                logger.error("[consume] the param : mode can not be null or empty");
                ResponseUtil.response(response, Consts.HTTP_FAILED_CODE,
                                      "the param : mode can not be null or empty", "", "''");
            } else {
                switch (mode.toLowerCase()) {
                    case "sync":
                        this.syncConsume(request, response, messagebus);
                        break;

                    case "async":
                        this.asyncConsume(request, response, messagebus);
                        break;

                    default:
                        logger.error("[consume] invalidate param : mode with value - " + mode);
                }
            }
        }
    }

    private void request(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
        try {
            if (!request.getMethod().toLowerCase().equals("post"))
                throw new InvalidParameterException("error http method : " + request.getMethod());

            String timeoutStr = request.getParameter("timeout");
            if (timeoutStr == null || timeoutStr.isEmpty())
                throw new NullPointerException("param : timeout can not be null or empty");

            long timeout;
            timeout = Long.valueOf(timeoutStr);

            if (timeout < Consts.MIN_CONSUME_TIMEOUT || timeout > Consts.MAX_CONSUME_TIMEOUT)
                throw new InvalidParameterException("invalid param : timeout it should be greater than :" + Consts.MIN_CONSUME_TIMEOUT +
                                                        "and less than : " + Consts.MAX_CONSUME_TIMEOUT);

            String queueName = request.getRequestURI().split("/")[3];
            String msgStr = request.getParameter("message");

            Message msg = MessageJSONSerializer.deSerialize(msgStr, MessageType.AppMessage);

            Messagebus messagebus = (Messagebus) (getServletContext().getAttribute(Consts.MESSAGE_BUS_KEY));

            IRequester requester = messagebus.getRequester();
            Message responseMsg = requester.request(msg, queueName, timeout);

            String respMsgStr = MessageJSONSerializer.serialize(responseMsg);
            ResponseUtil.response(response, Consts.HTTP_SUCCESS_CODE, "", "", respMsgStr);
        } catch (MessagebusUnOpenException e) {
            ResponseUtil.response(response, Consts.HTTP_FAILED_CODE, "occurs a messagebus unopen exception", "", "''");
        } catch (MessageResponseTimeoutException e) {
            ResponseUtil.response(response, Consts.HTTP_FAILED_CODE, "occurs a response timeout exception", "", "''");
        } catch (Exception e) {
            ResponseUtil.response(response, Consts.HTTP_FAILED_CODE, "occrus a exception : " + e.getMessage(), "", "''");
        }
    }

    private void response(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
        if (!request.getMethod().toLowerCase().equals("post")) {
            logger.error("error http method : " + request.getMethod());
            ResponseUtil.response(response, Consts.HTTP_FAILED_CODE, "error http method : " + request.getMethod(),
                                  "", "''");
        } else {
            String queueName = request.getRequestURI().split("/")[3];
            String msgStr = request.getParameter("message");

            Message msg = MessageJSONSerializer.deSerialize(msgStr, MessageType.AppMessage);

            Messagebus messagebus = (Messagebus) (getServletContext().getAttribute(Consts.MESSAGE_BUS_KEY));

            IResponser responser = null;
            try {
                responser = messagebus.getResponser();
                responser.responseTmpMessage(msg, queueName);
                ResponseUtil.response(response, Consts.HTTP_SUCCESS_CODE, "", "", "''");
            } catch (MessagebusUnOpenException e) {
                ResponseUtil.response(response, Consts.HTTP_FAILED_CODE,
                                      "occurs a MessagebusUnOpenException exception : " + e.getMessage(), "", "''");
            }
        }
    }

    private void syncConsume(HttpServletRequest request, HttpServletResponse response, Messagebus messagebus)
        throws ServletException, IOException {
        String queueName = request.getRequestURI().split("/")[3];
        String numStr = request.getParameter("num");
        int num = 0;

        List<Message> messages = null;

        try {
            if (numStr == null)
                throw new NullPointerException("[syncConsume] param : num can not be null or empty");

            try {
                num = Integer.valueOf(numStr);
            } catch (NumberFormatException e) {
                throw new InvalidParameterException("[syncConsume] invalidate param : num, it must be a integer!");
            }

            if (num < Consts.MIN_CONSUME_NUM || num > Consts.MAX_CONSUME_NUM)
                throw new InvalidParameterException("[syncConsume] invalidate param : num , it should be less than " +
                                                        Consts.MAX_CONSUME_NUM + " and greater than " + Consts.MIN_CONSUME_NUM);

            IConsumer consumer = messagebus.getConsumer();
            messages = consumer.consume(queueName, num);
            if (messages == null) {
                ResponseUtil.response(response, Consts.HTTP_SUCCESS_CODE, "", "", "\"[\"]");
            } else {
                String msgsStr = MessageJSONSerializer.serializeMessages(messages);
                ResponseUtil.response(response, Consts.HTTP_SUCCESS_CODE, "", "", msgsStr);
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
            ResponseUtil.response(response, Consts.HTTP_FAILED_CODE, e.getMessage(), "", "''");
        }
    }

    private void asyncConsume(HttpServletRequest request, HttpServletResponse response, Messagebus messagebus)
        throws ServletException, IOException {
        String queueName = request.getRequestURI().split("/")[2];

        //TODO
    }

}