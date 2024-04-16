FROM jeanollion/training_dnn:tf-2.7.1
RUN pip install DiSTNet2D==0.1.3
RUN wget https://gist.githubusercontent.com/jeanollion/4aea9bef9c4b98aa5f8084e1be5ed6ee/raw/training_core.py -O training_core.py
RUN wget https://gist.githubusercontent.com/jeanollion/789b9dbbda92da548401c7250f1631ad/raw/training_distnet2d.py -O train.py
ENTRYPOINT ["/bin/bash"]