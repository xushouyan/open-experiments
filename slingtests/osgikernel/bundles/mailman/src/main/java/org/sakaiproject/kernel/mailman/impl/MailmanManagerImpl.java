package org.sakaiproject.kernel.mailman.impl;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.cyberneko.html.parsers.DOMParser;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.ComponentContext;
import org.sakaiproject.kernel.api.proxy.ProxyClientService;
import org.sakaiproject.kernel.mailman.MailmanManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.html.HTMLCollection;
import org.w3c.dom.html.HTMLTableElement;
import org.w3c.dom.html.HTMLTableRowElement;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

/**
 * @scr.component immediate="true" label="MailManagerImpl"
 *                description="Interface to mailman"
 * @scr.property name="service.description"
 *                value="Handles management of mailman integration"
 * @scr.property name="service.vendor" value="The Sakai Foundation"
 * @scr.service interface="org.sakaiproject.kernel.mailman.MailmanManager"
 */
public class MailmanManagerImpl implements MailmanManager, ManagedService {
  
  private static final Logger LOGGER = LoggerFactory.getLogger(MailmanManagerImpl.class);
  
  /** @scr.reference */
  private ProxyClientService proxyClientService;
  
  /** @scr.property value="example.com" type="String" */
  private static final String MAILMAN_HOST = "mailman.host";
  
  /** @scr.property value="/cgi-bin/mailman" type="String" */
  private static final String MAILMAN_PATH = "mailman.path";
  
  /** @scr.property value="password" type="String" */
  private static final String LIST_ADMIN_PASSWORD = "mailman.listadmin.password";

  private ImmutableMap<String, String> configMap = ImmutableMap.of();
  
  private String getMailmanUrl(String stub) {
    return "http://" + configMap.get(MAILMAN_HOST) + configMap.get(MAILMAN_PATH) + stub;
  }
  
  public void createList(String listName, String ownerEmail, String password) throws MailmanException, HttpException, IOException {
    HttpClient client = new HttpClient(proxyClientService.getHttpConnectionManager());
    PostMethod post = new PostMethod(getMailmanUrl("/create"));
    NameValuePair[] parametersBody = new NameValuePair[] {
        new NameValuePair("listname", listName),
        new NameValuePair("owner", ownerEmail),
        new NameValuePair("password", password),
        new NameValuePair("confirm", password),
        new NameValuePair("auth", configMap.get(LIST_ADMIN_PASSWORD)),
        new NameValuePair("langs", "en"),
        new NameValuePair("notify", "1"),
        new NameValuePair("autogen", "0"),
        new NameValuePair("moderate", "0"),
        new NameValuePair("doit", "Create List")
    };
    post.setRequestBody(parametersBody);
    int result = client.executeMethod(post);
    try {
      if (result != HttpServletResponse.SC_OK) {
        throw new MailmanException("Unable to create list");
      }
    } finally {
      post.releaseConnection();
    }
  }

  public void deleteList(String listName, String listPassword) throws HttpException, IOException, MailmanException {
    HttpClient client = new HttpClient(proxyClientService.getHttpConnectionManager());
    PostMethod post = new PostMethod(getMailmanUrl("/rmlist/" + listName));
    NameValuePair[] parametersBody = new NameValuePair[] {
        new NameValuePair("password", listPassword),
        new NameValuePair("delarchives", "0"),
        new NameValuePair("doit", "Delete this list")
    };
    post.setRequestBody(parametersBody);
    int result = client.executeMethod(post);
    try {
      if (result != HttpServletResponse.SC_OK) {
        throw new MailmanException("Unable to create list");
      }
    } finally {
      post.releaseConnection();
    }
  }

  public boolean listHasMember(String listName, String listPassword, String memberEmail) throws HttpException, IOException, MailmanException, SAXException {
    HttpClient client = new HttpClient(proxyClientService.getHttpConnectionManager());
    GetMethod get = new GetMethod(getMailmanUrl("/admin/" + listName + "members"));
    NameValuePair[] parameters = new NameValuePair[] {
        new NameValuePair("findmember", memberEmail),
        new NameValuePair("setmemberopts_btn", ""),
        new NameValuePair("adminpw", listPassword)
    };
    get.setQueryString(parameters);
    int result = client.executeMethod(get);
    try {
      if (result != HttpServletResponse.SC_OK) {
        throw new MailmanException("Unable to search for member");
      }
      Document dom = parseHtml(get);
      NodeList inputs = dom.getElementsByTagName("INPUT");
      String unsubString = URLEncoder.encode(memberEmail, "utf8") + "_unsub";
      for (int i=0; i<inputs.getLength(); i++) {
        Node input = inputs.item(i);
        try {
          if (input.getAttributes().getNamedItem("name").getTextContent().equals(unsubString)) {
            return true;
          }
        } catch (NullPointerException npe) {
        }
      }
    } finally {
      get.releaseConnection();
    }
    return false;
  }
  
  public boolean addMember(String listName, String listPassword, String memberEmail) throws HttpException, IOException, MailmanException, SAXException {
    HttpClient client = new HttpClient(proxyClientService.getHttpConnectionManager());
    GetMethod get = new GetMethod(getMailmanUrl("/admin/" + listName + "members/add"));
    NameValuePair[] parameters = new NameValuePair[] {
      new NameValuePair("subscribe_or_invite", "0"),
      new NameValuePair("send_welcome_msg_to_this_batch", "0"),
      new NameValuePair("notification_to_list_owner", "0"),
      new NameValuePair("subscribees_upload", memberEmail),
      new NameValuePair("adminpw", listPassword)
    };
    get.setQueryString(parameters);
    int result = client.executeMethod(get);
    try {
      if (result != HttpServletResponse.SC_OK) {
        throw new MailmanException("Unable to add member");
      }
      Document dom = parseHtml(get);
      NodeList inputs = dom.getElementsByTagName("h5");
      if (inputs.getLength() == 0) {
        throw new MailmanException("Unable to read result status");
      }
      return "Successfully subscribed:".equals(inputs.item(0).getTextContent());
    } finally {
      get.releaseConnection();
    }
  }

  public boolean removeMember(String listName, String listPassword, String memberEmail) throws HttpException, IOException, MailmanException, SAXException {
    HttpClient client = new HttpClient(proxyClientService.getHttpConnectionManager());
    GetMethod get = new GetMethod(getMailmanUrl("/admin/" + listName + "members/remove"));
    NameValuePair[] parameters = new NameValuePair[] {
      new NameValuePair("send_unsub_ack_to_this_batch", "0"),
      new NameValuePair("send_unsub_notifications_to_list_owner", "0"),
      new NameValuePair("unsubscribees_upload", memberEmail),
      new NameValuePair("adminpw", listPassword)
    };
    get.setQueryString(parameters);
    int result = client.executeMethod(get);
    try {
      if (result != HttpServletResponse.SC_OK) {
        throw new MailmanException("Unable to add member");
      }
      Document dom = parseHtml(get);
      NodeList inputs = dom.getElementsByTagName("h5");
      if (inputs.getLength() == 0) {
        inputs = dom.getElementsByTagName("h3");
        if (inputs.getLength() == 0) {
          throw new MailmanException("Unable to read result status");
        }
      }
      return "Successfully Unsubscribed:".equals(inputs.item(0).getTextContent());
    } finally {
      get.releaseConnection();
    }
  }

  private Document parseHtml(HttpMethodBase method) throws SAXException, IOException {
    DOMParser parser = new DOMParser();
    parser.parse(new InputSource(method.getResponseBodyAsStream()));
    return parser.getDocument();
  }

  public List<String> getLists() throws HttpException, IOException, MailmanException, SAXException {
    HttpClient client = new HttpClient(proxyClientService.getHttpConnectionManager());
    GetMethod get = new GetMethod(getMailmanUrl("/admin"));
    List<String> lists = new ArrayList<String>();
    int result = client.executeMethod(get);
    try {
      if (result != HttpServletResponse.SC_OK) {
        LOGGER.warn("Got " + result + " from http request");
        throw new MailmanException("Unable to list mailinglists");
      }
      DOMParser parser = new DOMParser();
      parser.parse(new InputSource(get.getResponseBodyAsStream()));
      Document doc = parser.getDocument();
      NodeList tableNodes = doc.getElementsByTagName("table");
      if (tableNodes.getLength() < 2) {
        throw new MailmanException("Unrecognised page format.");
      }
      HTMLTableElement mainTable = (HTMLTableElement) tableNodes.item(0);
      HTMLCollection rows = mainTable.getRows();
      for (int i=4; i<rows.getLength(); i++) {
        lists.add(parseListNameFromRow((HTMLTableRowElement)rows.item(i)));
      }
    } finally {
      get.releaseConnection();
    }
    return lists;
  }

  private String parseListNameFromRow(HTMLTableRowElement item) throws MailmanException {
    HTMLCollection cells = item.getCells();
    if (cells.getLength() != 2) {
      throw new MailmanException("Unexpected table row format");
    }
    return cells.item(0).getTextContent();
  }

  void setServer(String mailmanHost) {
    Builder<String,String> builder = ImmutableMap.builder();
    builder.putAll(configMap);
    builder.put(MAILMAN_HOST, mailmanHost);
    configMap = builder.build();
  }

  void setMailmanPath(String mailmanPath) {
    Builder<String,String> builder = ImmutableMap.builder();
    builder.putAll(configMap);
    builder.put(MAILMAN_PATH, mailmanPath);
    configMap = builder.build();
  }

  ProxyClientService getProxyClientService() {
    return proxyClientService;
  }

  void setProxyClientService(ProxyClientService proxyClientService) {
    this.proxyClientService = proxyClientService;
  }

  public boolean isServerActive() throws HttpException, IOException, MailmanException,
      SAXException {
    HttpClient client = new HttpClient(proxyClientService.getHttpConnectionManager());
    GetMethod get = new GetMethod(getMailmanUrl("/admin"));
    int result = client.executeMethod(get);
    try {
      if (result != HttpServletResponse.SC_OK) {
        LOGGER.warn("Got " + result + " from http request");
        return false;
      }
      return true;
    } finally {
      get.releaseConnection();
    }
  }

  protected void activate(ComponentContext componentContext) {
    LOGGER.info("Got component initialization");
    Builder<String, String> builder = ImmutableMap.builder();
    for (Enumeration<?> e = componentContext.getProperties().keys(); e.hasMoreElements();) {
      String key = (String)e.nextElement();
      Object value = componentContext.getProperties().get(key);
      if (value instanceof String) {
        builder.put(key, (String) value);
      }
    }
    configMap = builder.build();
  }

  @SuppressWarnings("unchecked")
  public void updated(Dictionary config) throws ConfigurationException {
    LOGGER.info("Got config update");
    Builder<String, String> builder = ImmutableMap.builder();
    for (Enumeration<?> e = config.keys(); e.hasMoreElements();) {
      String k = (String) e.nextElement();
      builder.put(k, (String) config.get(k));
    }
    configMap = builder.build();
  }

}
