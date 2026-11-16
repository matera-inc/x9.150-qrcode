# The HTTPS front of a two-instance-payment-cycle.sh pairing (Story 11.3): nginx terminating TLS for
# both public hosts with the run's throwaway certificate, so a Java and a Go instance are reached
# the same way. Copied in rather than mounted, because Colima cannot bind-mount paths under /Volumes.
FROM nginx:1.27.5-alpine
COPY nginx.conf /etc/nginx/nginx.conf
COPY tls.pem tls.key /etc/nginx/x9/
