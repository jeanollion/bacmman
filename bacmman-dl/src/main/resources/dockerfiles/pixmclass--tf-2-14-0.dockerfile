FROM jeanollion/training_dnn:tf-2.14.0
RUN pip install --upgrade h5py==3.9.0
RUN pip install PixMClass==0.1.0
RUN wget https://gist.githubusercontent.com/jeanollion/4aea9bef9c4b98aa5f8084e1be5ed6ee/raw/training_core.py -O training_core.py
RUN wget https://gist.githubusercontent.com/jeanollion/60ad55e49a69dbf08e337ce97b030ce5/raw/training_pixmclass.py -O train.py
ENTRYPOINT ["/bin/bash"]