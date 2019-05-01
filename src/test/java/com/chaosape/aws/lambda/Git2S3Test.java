package com.chaosape.aws.lambda;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;

import java.util.Map;
import java.util.HashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.*;

public class Git2S3Test {

  private Map<String, String> env = null;

  @Before
  public void setUp() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode rootNode = mapper.readTree(new File("test_environment.json"));

    env = new HashMap<String, String>();

    env.put("REGION", rootNode.get("region").asText());
    env.put("BUCKET", rootNode.get("bucket").asText());


    env.put("TMP_REPO_DIR", "/tmp/repo");
  }


  @Test
  public void basicSuccessTest() {
    String initialString =
      "{\"body\":\"{\\\"repository\\\":{\\\"clone_url\\\":"
      + "\\\"https://github.com/chaosape/aws-link-shortener.git\\\"}}\"}";
    InputStream inputStream = new ByteArrayInputStream(initialString.getBytes());
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    Git2S3 gp = new Git2S3(env);
    try {
      gp.handleRequest(inputStream, outputStream, null);
      String actual = outputStream.toString("UTF-8");
      String expected =
        "{"
        + "\"headers\":"
          + "{\"Access-Control-Allow-Origin\":\"*\"},"
        + "\"isBase64Encoded\":false,"
        + "\"statusCode\":200"
        + "}";

      Assert.assertEquals("Unexpected JSON.", expected, actual);
    } catch(Exception e) {
      e.printStackTrace(System.err);
      Assert.fail();
    }
  }

  @Test
  public void basicFailureTest() {
    String initialString = "{ key : ";
    InputStream inputStream = new ByteArrayInputStream(initialString.getBytes());
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    Git2S3 gp = new Git2S3();
    try {
      gp.handleRequest(inputStream, outputStream, null);
      String actual = outputStream.toString("UTF-8");
      String expected =
        "{"
        + "\"headers\":"
          + "{\"Access-Control-Allow-Origin\":\"*\"},"
        + "\"isBase64Encoded\":false,"
        + "\"statusCode\":400"
        + "}";
      Assert.assertEquals("Unexpected JSON.", expected, actual);
    } catch(Exception e) {
    }
  }

}
