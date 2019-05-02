package com.chaosape.aws.lambda;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;

import java.nio.file.Files;
import java.nio.file.Paths;

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

    String content = new String (Files.readAllBytes(Paths.get("test_environment.json")));
    JsonNode rootNode = mapper.readTree(content);

    env = new HashMap<String, String>();

    env.put("REGION", rootNode.get("region").asText());
    env.put("BUCKET", rootNode.get("bucket").asText());
    env.put("GITHUB_TEST_REQUEST", rootNode.get("request").toString());
    env.put("CLONE_URL", rootNode.get("clone_url").asText());
    env.put("TMP_REPO_DIR", "/tmp/repo");
  }

  @Test
  public void basicSuccessTest() {
    String initialString = env.get("GITHUB_TEST_REQUEST");
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
