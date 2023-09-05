FROM jeanollion/training_dnn:tf-2.7.1
RUN pip install git+https://github.com/jeanollion/dataset_iterator.git
RUN pip install git+https://github.com/jeanollion/pix_mclass.git
RUN wget https://gist.githubusercontent.com/jeanollion/4aea9bef9c4b98aa5f8084e1be5ed6ee/raw -O training_core.py
RUN wget https://gist.githubusercontent.com/jeanollion/60ad55e49a69dbf08e337ce97b030ce5/raw -O train.py
ENTRYPOINT ["/bin/bash"]