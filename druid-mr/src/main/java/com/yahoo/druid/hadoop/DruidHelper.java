/**
* Copyright 2015 Yahoo! Inc. Licensed under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance with the License.
* You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
* Unless required by applicable law or agreed to in writing, software distributed
* under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
* CONDITIONS OF ANY KIND, either express or implied. See the License for the
* specific language governing permissions and limitations under the License.
* See accompanying LICENSE file.
*/
package com.yahoo.druid.hadoop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import io.druid.timeline.DataSegment;
import org.apache.commons.io.IOUtils;
import org.joda.time.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;


public class DruidHelper
{
  private static final Logger logger = LoggerFactory.getLogger(DruidHelper.class);

  public List<String> getSegmentPathsToLoad(final String dataSource, final Interval interval,
      final String storageDir, final String overlordUrl) {
    List<DataSegment> segmentsToLoad = getSegmentsToLoad(dataSource, interval, overlordUrl);

    return Lists.transform(
      segmentsToLoad, new Function<DataSegment, String>() {
        @Nullable
        @Override
        public String apply(DataSegment segment) {
          return (String)segment.getLoadSpec().get("path");
        }
      }
    );
  }

  protected List<DataSegment> getSegmentsToLoad(String dataSource, Interval interval, String overlordUrl) {
    String urlStr = "http://" + overlordUrl + "/druid/indexer/v1/action";
    logger.info("Sending request to overlord at " + urlStr);
    
    String requestJson = getSegmentListUsedActionJson(dataSource, interval.toString());
    logger.info("request json is " + requestJson);
    
    int numTries = 3; //TODO: should be configurable?
    for(int trial = 0; trial < numTries; trial++) {
      try {
        logger.info("attempt number {} to get list of segments from overlord", trial);
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("content-type", "application/json");
        conn.setUseCaches(false);
        conn.setDoOutput(true);
        conn.setConnectTimeout(60000); //TODO: 60 secs, shud be configurable?
        OutputStream out = conn.getOutputStream();
        out.write(requestJson.getBytes());
        out.close();
        int responseCode = conn.getResponseCode();
        if(responseCode == 200) {
          ObjectMapper mapper = DruidInitialization.getInstance().getObjectMapper();
          Map<String,Object> obj =
            mapper.readValue(conn.getInputStream(), new TypeReference<Map<String,Object>>(){});
          return mapper.convertValue(obj.get("result"), new TypeReference<List<DataSegment>>(){});
        } else {
          logger.warn("Attempt Failed to get list of segments from overlord. response code {} , response {}",
              responseCode, IOUtils.toString(conn.getInputStream()));
        }
      } catch(Exception ex) {
        logger.warn("Exception in getting list of segments from overlord", ex);
      }
      
      try {
        Thread.sleep(5000); //wait before next trial
      } catch(InterruptedException ex) {
        Throwables.propagate(ex);
      }
    }

    throw new RuntimeException(String.format( "failed to find list of segments, dataSource[%s], interval[%s], overlord[%s]", dataSource, interval, overlordUrl));
  }

  protected String getSegmentListUsedActionJson(String dataSource, String interval) {
    return "{\"task\": { \"type\" : \"noop\" }," +
      "\"action\": {" +
         "\"type\": \"segmentListUsed\"," +
         "\"dataSource\": \"" + dataSource+ "\"," +
         "\"interval\": \"" + interval + "\"" +
       "}}";
  }
}
