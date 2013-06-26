package com.googlecode.fascinator.portal.process;

import com.googlecode.fascinator.messaging.TransactionManagerQueueConsumer;
import com.googlecode.fascinator.common.JsonObject;
import com.googlecode.fascinator.common.JsonSimple;
import com.googlecode.fascinator.common.solr.SolrDoc;
import com.googlecode.fascinator.common.solr.SolrResult;
import com.googlecode.fascinator.api.indexer.Indexer;
import com.googlecode.fascinator.api.indexer.SearchRequest;
import com.googlecode.fascinator.messaging.EmailNotificationConsumer;
import com.googlecode.fascinator.common.messaging.MessagingException;
import com.googlecode.fascinator.common.messaging.MessagingServices;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.DefaultAuthenticator;
import org.apache.commons.mail.SimpleEmail;
import org.apache.commons.mail.HtmlEmail;

import org.json.simple.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

public class EmailNotifier implements Processor {

    private Logger log = LoggerFactory.getLogger(EmailNotifier.class);
    
    private String host;
    private String port;
    private String tls;
    private String ssl;
    private String from;
    private String to;
    private String username;
    private String password;
    private String testmode;
    private String redirect;
    
    private void init(JsonSimple config) {
        host = config.getString("", "host");
        port = config.getString("", "port");
        from = config.getString("", "from");
        to = config.getString("", "to");
        username = config.getString("", "username");
        password = config.getString("", "password");
        tls = config.getString("false", "tls");
        ssl = config.getString("false", "ssl");
        testmode = config.getString("false", "testmode");
        redirect = config.getString("false", "redirect");
        Properties props = System.getProperties();

        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", tls);
        
    }
    
    private String replaceVars(SolrDoc solrDoc, String text, List<String> vars, JsonSimple config) {
        for (String var : vars) {
            String varField = "" ;
            if(var.equals("$_owner_name") || var.equals("$_owner_email"))
            {
                varField = "owner";
            }
            else
            {
                varField = config.getString("", "mapping", var);
            }

            log.debug("Replacing '" + var + "' using field '" + varField + "'");

            String replacement = solrDoc.getString(var, varField);
            if (replacement == null || "".equals(replacement)) {
                JSONArray arr = solrDoc.getArray(varField);
                if (arr != null) {
                    replacement = (String) arr.get(0);
                }
            }

            if (replacement == null)
            {
                replacement = "" ;
            }

            if(var.equals("$_owner_name") || var.equals("$_owner_email"))
            {
                String[] ias = parseEmail(replacement);
                if(var.equals("$_owner_name"))
                {
                    replacement = ias[0] ;
                }
                if(var.equals("$_owner_email"))
                {
                    replacement = ias[1] ;
                }
            }

            text = text.replace(var, replacement);
        }
        return text;
    }
    
    @Override
    public boolean process(String id, String inputKey, String outputKey,
            String stage, String configFilePath, HashMap dataMap)
            throws Exception {

        JsonSimple config = new JsonSimple(new File(configFilePath));
        init(config);

        Indexer indexer = (Indexer) dataMap.get("indexer");
        
        ArrayList<String> failedOids = new ArrayList<String>();
        List<String> oids = (List<String>) dataMap.get(inputKey);
        String subjectTemplate = config.getString("", "subject");
        String bodyTemplate = config.getString("", "body");
        List<String> vars = config.getStringList("vars");
        for (String oid : oids) {
            log.debug("Sending email notification for oid:"+oid);
            // get the solr doc 
            SearchRequest searchRequest = new SearchRequest("id:" + oid);
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            indexer.search(searchRequest, result);
            SolrResult resultObject = new SolrResult(result.toString());
            List<SolrDoc> results = resultObject.getResults();
            SolrDoc solrDoc = results.get(0);
            String subject = replaceVars(solrDoc, subjectTemplate, vars, config);
            String body = replaceVars(solrDoc, bodyTemplate, vars, config);
            String recipient = to;
            if (to.contains("$")) {
                recipient = replaceVars(solrDoc, to, vars, config);
            }
            if (!email(oid, recipient, subject, body)) {
                failedOids.add(oid);
            }
        }
        dataMap.put(outputKey, failedOids);
        return true;
    }
    
    private boolean email(String oid, String recipient, String subject, String body) {
        String[] recs= recipient.split(",");
        ArrayList rec_sent = new ArrayList() ;
        for (String rec : recs) {
            String[] ias = parseEmail(rec);
            String rec_name = ias[0] ;
            String rec_email = ias[1] ;
            String message_body = body ;
            message_body= message_body.replace("#REC_NAME", rec_name);
            message_body= message_body.replace("#REC_EMAIL", rec_email);

            if(rec_sent.contains(rec_email) || rec_email.indexOf('@')==-1)
            {
                continue ;
            }

            try {
                // use HTML email format
                HtmlEmail email = new HtmlEmail();
                log.debug("Email host: " + host);
                log.debug("Email port: " + port);
                log.debug("Email username: " + username);
                log.debug("Email from: " + from);
                log.debug("Email to: " + recipient);
                log.debug("Email Subject is: " + subject);
                log.debug("Email Body is: " + body);
                email.setHostName(host);
                email.setSmtpPort(Integer.parseInt(port));
                email.setAuthenticator(new DefaultAuthenticator(username, password));
                // the method setSSL is deprecated on the newer versions of commons email...
                email.setSSL("true".equalsIgnoreCase(ssl));
                email.setTLS("true".equalsIgnoreCase(tls));
                email.setFrom(from);
                email.setSubject(subject);

                if( "true".equalsIgnoreCase(testmode) )
                {
                    message_body += "<p>TESTMODE: was sent to " + rec_email ;
                    rec_email = redirect ;
                }

                email.addTo(rec_email);
                email.setHtmlMsg(message_body);
                email.addTo("redbox-alerts@deakin.edu.au");
                email.send();

                rec_sent.add(rec_email);
            } catch (Exception ex) {
                log.debug("Error sending notification mail for oid:" + oid + " to " + rec, ex);
                return false;
            }
        }
        return true;
    }

    private String[] parseEmail(String email)
    {
        int lt = email.indexOf('<') ;
        int gt = email.indexOf('>') ;
        if(lt>-1 && gt>-1 && lt+1<=email.length())
        {
            String name = email.substring(0, lt) ;
            String address = email.substring(lt+1, gt) ;
            return new String[]{name,address};
        }

        return new String[]{email,email};
    }
}
