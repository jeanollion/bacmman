FROM jeanollion/training_dnn:tf-2.14.0
RUN pip install --upgrade h5py==3.11.0
RUN pip install DiSTNet2D
RUN wget https://gist.githubusercontent.com/jeanollion/4aea9bef9c4b98aa5f8084e1be5ed6ee/raw/training_core.py -O training_core.py
RUN wget https://gist.githubusercontent.com/jeanollion/8035170d925598817be05dafbea05e89/raw/training_distnet2d_seg.py -O train.py
RUN chmod a+r /training_core.py
RUN chmod a+r /train.py
ENV NUMBA_NUM_THREADS=1
ENTRYPOINT ["/bin/bash"]