FROM jeanollion/training_dnn:tf-2.14.0
RUN pip install --upgrade h5py==3.11.0
RUN pip install git+https://git@github.com/jeanollion/dataset_iterator.git@dev
RUN pip install git+https://git@github.com/jeanollion/pix_mclass.git@dev
RUN pip install git+https://git@github.com/jeanollion/distnet2d.git@dev

RUN wget https://gist.githubusercontent.com/jeanollion/7b156bdbd7769f7a0a64b6774550ff4d/raw/predict_dev.py -O predict.py
RUN chmod a+r predict.py

CMD ["python", "predict.py"]