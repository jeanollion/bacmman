FROM jeanollion/training_dnn:tf-2.14.0
RUN pip install --upgrade h5py==3.9.0
RUN pip install git+https://github.com/jeanollion/dataset_iterator.git
RUN pip install DiSTNet2D==0.1.5
RUN wget https://gist.githubusercontent.com/jeanollion/4aea9bef9c4b98aa5f8084e1be5ed6ee/raw/training_core.py -O training_core.py
RUN wget https://gist.githubusercontent.com/jeanollion/8035170d925598817be05dafbea05e89/raw/training_distnet2d_seg.py -O train.py
ENTRYPOINT ["/bin/bash"]