FROM docker.elastic.co/elasticsearch/elasticsearch:9.2.2
RUN bin/elasticsearch-plugin install analysis-nori
