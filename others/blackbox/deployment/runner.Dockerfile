# The black-box suite, as a container (Story 3.6). Built by run-against-image.sh from a staged
# context holding only the suite, the bundled test CA, openapi.yaml and ADR-0035, at their
# repository paths.
#
# It joins the network namespace of the service under test (compose `network_mode`), so the
# service reaches the suite's CRL server and test payee on 127.0.0.1, as when both run on one host.
FROM python:3.13.9-slim

# The database inspector of the build under test: mongo (Java, the default) or postgres (Go).
ARG X9_DB_EXTRA=mongo
WORKDIR /suite
COPY others/blackbox/requirements.txt others/blackbox/requirements-db-${X9_DB_EXTRA}.txt others/blackbox/
RUN pip install --no-cache-dir -q -r others/blackbox/requirements-db-${X9_DB_EXTRA}.txt

COPY . .
ENV PYTHONDONTWRITEBYTECODE=1
ENTRYPOINT ["python", "others/blackbox/run.py"]
CMD ["-q", "-p", "no:cacheprovider"]
