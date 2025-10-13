FROM jeanollion/training_dnn:tf-2.14.0
RUN pip install --upgrade h5py==3.11.0
RUN pip install git+https://git@github.com/jeanollion/dataset_iterator.git@dev
RUN pip install git+https://git@github.com/jeanollion/pix_mclass.git@dev
RUN wget https://gist.githubusercontent.com/jeanollion/4aea9bef9c4b98aa5f8084e1be5ed6ee/raw/training_core_dev.py -O training_core.py
RUN wget https://gist.githubusercontent.com/jeanollion/60ad55e49a69dbf08e337ce97b030ce5/raw/training_pixmclass_dev.py -O train.py
RUN chmod a+r /training_core.py
RUN chmod a+r /train.py
ENV NUMBA_NUM_THREADS=1
ENTRYPOINT ["/bin/bash"]