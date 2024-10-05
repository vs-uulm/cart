#!/bin/bash

if [ "$MICROBENCHMARK" == false ]
then
  /home/threshsig-wrapper/gRPC_Server -1 2 4
else
  /home/threshsig-wrapper/Microbenchmark "${NUM_SIGNER}" "${THRESHOLD}"
fi

