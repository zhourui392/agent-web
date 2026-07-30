# NATIVE diagnosis monitoring

`native-diagnosis-alerts.yml` is a Prometheus rule group for the metrics exported from the
loopback-only Spring Boot management socket (`127.0.0.1:18093/actuator/prometheus` by default).

Production deployment must load this rule file into Prometheus (or translate the same expressions
to the selected monitoring platform), route `critical` and `warning` labels to an owned receiver,
and keep the management port inaccessible from the public reverse proxy. A deployment is not
considered production-ready until the collector target is `UP` and a test alert has reached the
configured receiver.
